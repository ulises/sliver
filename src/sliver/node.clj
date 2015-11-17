(ns sliver.node
  (:require [borges.type :as t]
            [borges.decoder :as d]
            [bytebuffer.buff :refer [take-ubyte]]
            [co.paralleluniverse.pulsar.actors :as a]
            [co.paralleluniverse.pulsar.core :as c]
            [sliver.epmd :as epmd]
            [sliver.handshake :as h]
            [sliver.handler :as ha]
            [sliver.node-interface :as ni]
            [sliver.protocol :as p]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as timbre])
  (:import [co.paralleluniverse.fibers.io FiberSocketChannel
            AsyncFiberServerSocketChannel]))

(declare !*)

(defn- writer-name [other-node]
  (symbol (str (util/plain-name other-node) "-writer")))

(defn- reader-name [other-node]
  (symbol (str (util/plain-name other-node) "-reader")))

(defn- register-shutdown [node name]
  (swap! (:state node) update-in
         [:shutdown-notify] conj name))

(defrecord Node [node-name host cookie handlers state pid-tracker ref-tracker
                 actor-tracker reverse-actor-tracker actor-registry]
  ni/NodeP
  (connect [node other-node]
    (c/join
     (ni/actor-for
      node
      (ni/spawn node
                #(let [{:keys [status connection]} (h/initiate-handshake node other-node)]
                   (if (= :ok status)
                     (ni/handle-connection node connection other-node))
                   node)))))

  (get-writer [node other-node]
    (let [other-name (writer-name other-node)]
      (ni/whereis node other-name)))

  (handle-connection [node connection other-node]
    (let [reader (ni/spawn
                  node
                  #(do
                     (ni/register node (reader-name other-node) (ni/self node))
                     (timbre/debug (format "%s: Reader for %s"
                                           (util/plain-name node)
                                           (util/plain-name other-node)))
                     (p/do-loop connection
                                (fn handler [[control message]]
                                  (let [[from to] (p/parse-control control)]
                                    (dorun
                                     (for [handler handlers]
                                       (handler node from to message))))))))
          writer (ni/spawn
                  node
                  #(do
                     (ni/register node (writer-name other-node) (ni/self node))
                     (timbre/debug (format "%s: Writer for %s"
                                           (util/plain-name node)
                                           (util/plain-name other-node)))
                     (loop []
                       (a/receive
                        [m]
                        [:send-msg pid msg]
                        (do (p/send-message connection pid msg)
                            (recur))

                        [:send-reg-msg from to msg]
                        (do (p/send-reg-message connection from to msg)
                            (recur))

                        ;; if the reader dies, we should close
                        ;; everything and finish
                        [:exit _ref _actor throwable]
                        (do (timbre/debug (format "%s: Reader died."
                                                  (writer-name other-node)))
                            (.close ^FiberSocketChannel connection))

                        :shutdown (.close ^FiberSocketChannel connection)
                        :else (do (timbre/debug "ELSE:" m)
                                  (recur))))))]
      (ni/link node writer reader)
      (register-shutdown node (writer-name other-node))
      :ok))

  (start [node]
    (let [name          (util/plain-name node)
          port          (+ 1024 (rand-int 50000))
          server-thread (ni/spawn
                         node
                         (fn []
                           (let [server (tcp/server host port)]
                             (ni/register node 'server (ni/self node))
                             (register-shutdown node 'server)
                             (loop []
                               (timbre/debug (format
                                              "%s: Accepting connections on %s"
                                              name port))
                               (let [conn (.accept server)]
                                 (timbre/debug "Accepted connection:" conn)
                                 (let [{:keys [status connection other-node]}
                                       (h/handle-handshake node conn)]
                                   (if (= :ok status)
                                     (do (timbre/debug "Connection established."
                                                       " Saving to:"
                                                       other-node)
                                         (ni/handle-connection node
                                                               connection
                                                               other-node))
                                     (timbre/debug "Handshake failed :("))))
                               (recur)))))
          wait-for-epmd (c/promise)
          epmd-actor    (ni/spawn
                         node
                         #(let [epmd-conn   (epmd/client)
                                epmd-result (epmd/register epmd-conn
                                                           (util/plain-name
                                                            name)
                                                           port)]
                            (if (not (= :ok (:status epmd-result)))
                              (timbre/debug "Error registering with EPMD:"
                                            name " -> " epmd-result))
                            (ni/register node 'epmd-socket (ni/self node))
                            (register-shutdown node 'epmd-socket)
                            (deliver wait-for-epmd true)
                            (a/receive :shutdown
                                       (do (timbre/debug
                                            (format "%s: closing epmd connection "
                                                    (util/plain-name node)))
                                           (.close ^FiberSocketChannel epmd-conn)))))]
      @wait-for-epmd
      node))

  (stop [node]
    (timbre/debug (format "%s: stopping..." (util/plain-name node)))
    (dorun
     (for [writer (:shutdown-notify @state)]
       (do (timbre/debug "Notifying:" writer)
           (ni/! node writer :shutdown))))
    node)

  ;; pid(0, 42, 0) ! message
  (send-message [node pid message]
    (when pid
      (let [other-node-name (util/plain-name (name (:node pid)))
            other-node      {:node-name other-node-name}]
        (if (= other-node-name (util/plain-name node))
          (when-let [actor (ni/actor-for node pid)] ;; if actor doesn't exist, ignore
            (a/! actor message))
          (if-let [writer-pid (ni/get-writer node other-node)]
            (ni/send-message node writer-pid [:send-msg pid message])
            (do (timbre/debug
                 (format "Couldn't find writer for %s. Please double check this."
                         other-node-name))))))))

  ;; equivalent to {to, 'name@host'} ! message
  (send-registered-message [node from to other-node message]
    ;; check other-node first, if local, check registered actor
    (if (= (util/plain-name other-node) (util/plain-name node))
      (if-let [pid (ni/whereis node to)]
        (do (timbre/debug "Sending " message " to: " to " -- " (ni/actor-for node pid))
            (a/! (ni/actor-for node pid) message))
        (do (timbre/debug "WARNING: couldn't find local actor " to)))
      (if-let [writer-pid (ni/get-writer node other-node)]
        (ni/send-message node writer-pid [:send-reg-msg from to message])
        (do (timbre/debug
             (format "Couldn't find writer for %s. Please double check this."
                     other-node))))))

  (! [node maybe-actor-or-pid message]
    (!* node maybe-actor-or-pid message))

  ;; creates a new pid. This is internal, and is likely to be used in
  ;; conjunction with some form of custom spawn implementation
  (pid [node]
    (dosync
     (let [current-pid    (:pid @pid-tracker)
           current-serial (:serial @pid-tracker)
           new-pid        (t/pid (symbol (util/fqdn node))
                                 current-pid
                                 current-serial
                                 (:creation @pid-tracker))]
       (let [[next-pid next-serial]
             (if (> (inc current-pid) 0x3ffff)
               [0 (if (> (inc current-serial) 0x1fff)
                    0
                    (inc current-serial))]
               [(inc current-pid) current-serial])]
         (alter pid-tracker assoc
                :pid next-pid
                :serial next-serial)
         new-pid))))

  (track-pid [node pid actor]
    (dosync
     (alter actor-tracker assoc pid actor)
     (alter reverse-actor-tracker assoc actor pid))
    pid)

  (untrack [node pid]
    (dosync
     (let [actor (ni/actor-for node pid)]
       (alter actor-tracker dissoc pid)
       (alter reverse-actor-tracker dissoc actor)
       pid)))

  (monitor [node pid]
    (when-let [actor (ni/actor-for node pid)]
      (a/watch! actor)))

  (spawn-monitor [node f]
    (let [pid (ni/spawn node f)]
      (ni/monitor node pid)))

  (demonitor [node pid monitor]
    (when-let [actor (ni/actor-for node pid)]
      (a/unwatch! actor monitor)))

  (link [node pid]
    (when-let [actor (ni/actor-for node pid)]
      (a/link! actor)))

  (link [node pid1 pid2]
    (when-let [actor1 (ni/actor-for node pid1)]
      (when-let [actor2 (ni/actor-for node pid2)]
       (a/link! actor1 actor2))))

  (actor-for [node pid]
    (get @actor-tracker pid))

  (pid-for [node actor]
    (get @reverse-actor-tracker actor))

  (name-for [node pid]
    (first (first (filter (fn [[k v]]
                            (= pid v)) @actor-registry))))

  (self [node]
    (if-let [pid (ni/pid-for node @a/self)]
        pid (recur)))

  (spawn [node f]
    (ni/spawn node f {:trap false}))

  (spawn [node f {:keys [trap] :or {trap false}}]
    (let [pid (ni/pid node)]
      ;; it's likely that there's a race condition here between the spawning
      ;; of the process and it being registered. An actor might want to
      ;; immediately perform node ops that depend on the registry and its own
      ;; registered pid and they won't be available, etc. Figure out how to
      ;; spawn an actor in a "paused" mode
      (ni/track-pid node pid (a/spawn :trap trap f))
      (ni/! node '_dead-processes-reaper [:monitor pid])
      pid))

  ;; these are certainly NOT atomic
  (spawn-link [node f]
    (ni/spawn-link node f {:trap false}))

  (spawn-link [node f opts]
    (let [pid (ni/spawn node f opts)]
      (ni/link node (ni/self node) pid)
      pid))

  ;; this is likely to suffer from a race condition just like track-pid
  ;; in particular because of the use of whereis, we probably need a CAS
  ;; type approach here
  (register [node name pid]
    (when (and name
               (not (ni/whereis node name)))
      (swap! actor-registry assoc name pid)
      (timbre/debug "Registering " pid " as " name)
      name))

  (unregister [node name]
    (swap! actor-registry dissoc name)
    name)

  (whereis [node name]
    (get @actor-registry name))

  (make-ref [node pid]
    (dosync
     (let [creation      (:creation @ref-tracker)
           id            (:id @ref-tracker)
           new-reference (t/reference (symbol (util/fqdn node)) id creation)]
       (let [next-creation (inc creation)]
         (alter ref-tracker assoc :creation next-creation)
         new-reference)))))

(defmulti !* (fn [node maybe-pid-or-actor message] (type maybe-pid-or-actor)))

(defn- !-name [node actor-name message]
  (let [actor-pid (ni/whereis node actor-name)]
    (ni/send-message node actor-pid message)))

(defmethod !* clojure.lang.Symbol
  [node actor-name message]
  (!-name node actor-name message))

(defmethod !* clojure.lang.Keyword
  [node actor-name message]
  (!-name node actor-name message))

(defmethod !* java.lang.String
  [node actor-name message]
  (!-name node actor-name message))

(defmethod !* borges.type.Pid
  [node pid message]
  (ni/send-message node pid message))

(defmethod !* clojure.lang.PersistentVector
  [node [actor other-node] message]
  (timbre/debug "Sending reg msg to:" actor " on " other-node)
  (ni/send-registered-message node (ni/self node) actor other-node message))

(defmethod !* nil [_ _ _])

(defn node
  ([name cookie] (node name cookie [ha/handle-messages]))
  ([name cookie handlers]
   (let [[node-name host] (util/maybe-split name)
         node             (Node. node-name (or host "localhost") cookie handlers
                                 (atom {:shutdown-notify #{}})
                                 (ref {:pid 0 :serial 0 :creation 0})
                                 (ref {:creation 0 :id [0 1 1]})
                                 (ref {})
                                 (ref {})
                                 (atom {}))]
     (timbre/debug node-name "::" host)
     (ni/spawn node
               (fn []
                 (ni/register node '_dead-processes-reaper (ni/self node))
                 (register-shutdown node '_dead-processes-reaper)
                 (loop []
                   (a/receive [m]
                              [:monitor pid] (do (ni/monitor node pid)
                                                 (recur))
                              [:exit _ref actor _reason]
                              (let [pid (ni/pid-for node actor)]
                                (ni/untrack node pid)
                                (ni/unregister node (ni/name-for node pid))
                                (recur))
                              [:shutdown] (do (timbre/debug "Reaper shutting down...")
                                              :ok)))))
     node)))
