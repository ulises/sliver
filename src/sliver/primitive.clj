(ns sliver.primitive
  (:require [co.paralleluniverse.pulsar.actors :as a]
            [co.paralleluniverse.pulsar.core :as c]
            [borges.type :as t]
            [sliver.io :as io]
            [sliver.protocol :as p]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as timbre]))

(defn #^{:rebind true :dynamic true}
  whereis [{:keys [actor-registry] :as node} name]
  (get @actor-registry name))

(defn #^{:rebind true :dynamic true}
  actor-for [{:keys [actor-tracker] :as node} pid]
  (get @actor-tracker pid))

(defn #^{:rebind true :dynamic true}
  pid-for [{:keys [reverse-actor-tracker] :as node} actor]
  (get @reverse-actor-tracker actor))

(defn #^{:rebind true :dynamic true}
  name-for [{:keys [actor-registry] :as node} pid]
  (first (first (filter (fn [[k v]]
                          (= pid v)) @actor-registry))))

(defn #^{:rebind true :dynamic true}
  self [node]
  (if-let [pid (pid-for node @a/self)]
    pid (recur node)))

(defn #^{:rebind true :dynamic true}
  get-writer [node other-node]
  (let [other-name (util/writer-name other-node)]
    (whereis node other-name)))

;; pid(0, 42, 0) ! message
(defn #^{:rebind true :dynamic true}
  send-message [node pid message]
  (when pid
    (let [other-node-name (util/plain-name (name (:node pid)))
          other-node      {:node-name other-node-name}]
      (if (= other-node-name (util/plain-name node))
        (when-let [actor (actor-for node pid)] ;; if actor doesn't exist, ignore
          (a/! actor message))
        (if-let [writer-pid (get-writer node other-node)]
          (send-message node writer-pid [:send-msg pid message])
          (do (timbre/debug
               (format "Couldn't find writer for %s. Please double check this."
                       other-node-name)))))
      message)))

;; equivalent to {to, 'name@host'} ! message
(defn #^{:rebind true :dynamic true}
  send-registered-message [node from to other-node message]
  ;; check other-node first, if local, check registered actor
  (if (= (util/plain-name other-node) (util/plain-name node))
    (if-let [pid (whereis node to)]
      (do (timbre/debug "Sending " message " to: " to " -- " (actor-for
                                                              node pid))
          (a/! (actor-for node pid) message))
      (do (timbre/debug "WARNING: couldn't find local actor " to)))
    (if-let [writer-pid (get-writer node other-node)]
      (do (send-message node writer-pid [:send-reg-msg from to message])
          message)
      (do (timbre/debug
           (format "Couldn't find writer for %s. Please double check this."
                   other-node))))))

(defmulti !* (fn [node maybe-pid-or-actor message] (type maybe-pid-or-actor)))

(defn- !-name [node actor-name message]
  (let [actor-pid (whereis node actor-name)]
    (send-message node actor-pid message)))

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
  (send-message node pid message))

(defmethod !* clojure.lang.PersistentVector
  [node [actor other-node] message]
  (timbre/debug "Sending reg msg to:" actor " on " other-node)
  (send-registered-message node (self node) actor other-node message))

(defmethod !* nil [_ _ _])

(defn #^{:rebind true :dynamic true}
  ! [node maybe-actor-or-pid message]
  (!* node maybe-actor-or-pid message))

;; creates a new pid. This is internal, and is likely to be used in
;; conjunction with some form of custom spawn implementation
(defn #^{:rebind true :dynamic true}
  pid [{:keys [pid-tracker] :as node}]
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

(defn #^{:rebind true :dynamic true}
  track-pid [{:keys [actor-tracker reverse-actor-tracker] :as node} pid actor]
  (dosync
   (alter actor-tracker assoc pid actor)
   (alter reverse-actor-tracker assoc actor pid))
  pid)

(defn #^{:rebind true :dynamic true}
  untrack [{:keys [actor-tracker reverse-actor-tracker] :as node} pid]
  (dosync
   (let [actor (actor-for node pid)]
     (alter actor-tracker dissoc pid)
     (alter reverse-actor-tracker dissoc actor)
     pid)))

(defn #^{:rebind true :dynamic true}
  monitor [node pid]
  (when-let [actor (actor-for node pid)]
    (a/watch! actor)))

(defn #^{:rebind true :dynamic true}
  demonitor [node pid monitor]
  (when-let [actor (actor-for node pid)]
    (a/unwatch! actor monitor)))

(defn #^{:rebind true :dynamic true}
  link
  ([node pid]
   (when-let [actor (actor-for node pid)]
     (a/link! actor)))
  ([node pid1 pid2]
   (when-let [actor1 (actor-for node pid1)]
     (when-let [actor2 (actor-for node pid2)]
       (a/link! actor1 actor2)))))

(defn #^{:rebind true :dynamic true}
  spawn
  ([node f]
   (spawn node f {:trap false}))
  ([node f {:keys [trap] :or {trap false}}]
   (let [pid (pid node)]
     ;; it's likely that there's a race condition here between the spawning
     ;; of the process and it being registered. An actor might want to
     ;; immediately perform node ops that depend on the registry and its own
     ;; registered pid and they won't be available, etc. Figure out how to
     ;; spawn an actor in a "paused" mode
     (track-pid node pid (a/spawn :trap trap f))
     (! node '_dead-processes-reaper [:monitor pid])
     pid)))

(defn #^{:rebind true :dynamic true}
  spawn-monitor [node f]
  (let [pid (spawn node f)]
    (monitor node pid)))

;; these are certainly NOT atomic
(defn #^{:rebind true :dynamic true}
  spawn-link
  ([node f]
   (spawn-link node f {:trap false}))
  ([node f opts]
   (let [pid (spawn node f opts)]
     (link node (self node) pid)
     pid)))

;; this is likely to suffer from a race condition just like track-pid
;; in particular because of the use of whereis, we probably need a CAS
;; type approach here
(defn #^{:rebind true :dynamic true}
  register [{:keys [actor-registry] :as node} name pid]
  (when (and name
             (not (whereis node name)))
    (swap! actor-registry assoc name pid)
    (timbre/debug "Registering " pid " as " name)
    name))

(defn #^{:rebind true :dynamic true}
  unregister [{:keys [actor-registry] :as node} name]
  (swap! actor-registry dissoc name)
  name)

(defn #^{:rebind true :dynamic true}
  make-ref [{:keys [ref-tracker] :as node} pid]
  (dosync
   (let [creation      (:creation @ref-tracker)
         id            (:id @ref-tracker)
         new-reference (t/reference (symbol (util/fqdn node)) id creation)]
     (let [next-creation (inc creation)]
       (alter ref-tracker assoc :creation next-creation)
       new-reference))))

(defn #^{:rebind true :dynamic true}
  handle-connection [{:keys [handlers] :as node} connection other-node]
  (let [reader (spawn node (io/reader node connection handlers other-node))
        writer (spawn node (io/writer connection other-node))]
    (register node (util/reader-name other-node) reader)
    (register node (util/writer-name other-node) writer)
    (link node writer reader)
    (util/register-shutdown node (util/writer-name other-node))
    :ok))
