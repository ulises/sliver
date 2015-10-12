(ns sliver.node
  (:require [borges.type :as t]
            [borges.decoder :as d]
            [bytebuffer.buff :refer [take-ubyte]]
            [co.paralleluniverse.pulsar.actors :as a]
            [sliver.epmd :as epmd]
            [sliver.handshake :as h]
            [sliver.protocol :as p]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as timbre])
  (:import [java.nio.channels SocketChannel]))

(defprotocol NodeP
  "A simple protocol for Erlang nodes."

  (connect
    [node other-node]
    "Connects to an Erlang node.")

  (start [node]
    "Starts the node. The node registers with epmd (not started if not
running) and starts listening for incoming connections.")

  (stop [node]
    "Stops node. Closes all connections, etc.")

  (send-message [node pid message]
    "Sends a message to the process pid@host.")

  (send-registered-message [node from-pid process-name other-node message]
    "Sends a registered message to the process process-name@other-node.")

  (pid [node]
    "Creates a new pid.")

  (track-pid [node pid actor]
    "Keeps pids connected to actors")

  (actor-for [node pid]
    "Returns the actor linked to pid")

  (pid-for [node actor]
    "Returns the pid linked to actor")

  (self [node]
    "Returns pid for current actor")

  (spawn [node f]
    "Spawns function f as a process.")

  (register [node pid name]
    "Registers a process under a name")

  (whereis [node name]
    "Finds an actor's pid based on its name")

  (make-ref [node pid]
    "Creates a new reference.")

  (save-connection [node other-node connection]
    "Saves the connection to other-node")

  (get-connection [node other-node]
    "Gets the socket to other-node")

  (handle-connection [node connection]
    "Handles a connection after the handshake has been successful"))

(defrecord Node [node-name host cookie handlers state pid-tracker ref-tracker
                 actor-tracker reverse-actor-tracker actor-registry]
  NodeP
  (connect [node other-node]
    (let [{:keys [status connection]} (h/initiate-handshake node other-node)]
      (if (= :ok status)
        (do (save-connection node other-node connection)
            (handle-connection node connection)))
      node))

  (save-connection [node other-node connection]
    (swap! state update-in [(util/plain-name other-node)]
           assoc :connection connection))

  (get-connection [node other-node]
    (get-in @state [(util/plain-name other-node) :connection]))

  (handle-connection [node connection]
    (future
      (p/do-loop connection
                 (fn handler [[control message]]
                   (let [[from to] (p/parse-control control)]
                     (dorun
                      (for [handler handlers]
                        (handler node from to message))))))))

  (start [node]
    (let [name          (util/plain-name node)
          port          (+ 1024 (rand-int 50000))
          server        (tcp/server host port)
          epmd-conn     (epmd/client)
          server-thread (future
                          (loop []
                            (timbre/debug "Accepting connections...")
                            (let [conn (.accept server)]
                              (timbre/debug "Accepted connection:" conn)
                              (let [{:keys [status connection other-node]}
                                    (h/handle-handshake node conn)]
                                (if (= :ok status)
                                  (do (timbre/debug "Connection established."
                                                    " Saving to:"
                                                    other-node)
                                      (save-connection node other-node connection)
                                      (handle-connection node connection))
                                  (timbre/debug "Handshake failed :("))))
                            (recur)))]
      (let [epmd-result (epmd/register epmd-conn (util/plain-name name) port)]
        (if (not (= :ok (:status epmd-result)))
          (timbre/debug "Error registering with EPMD:" name " -> " epmd-result)))
      (swap! state update-in [:server-socket] assoc :connection server)
      (swap! state update-in [:epmd-socket] assoc :connection epmd-conn)
      node))

  (stop [node]
    (dorun
     (for [{:keys [connection]} (vals @state)]
       (do (timbre/debug "Closing:" connection)
           (.close ^SocketChannel connection))))
    node)

  ;; pid(0, 42, 0) ! message
  (send-message [node pid message]
    (let [other-node-name (util/plain-name (name (:node pid)))
          other-node      {:node-name other-node-name}]
      (if (= other-node-name (util/plain-name node))
        (when-let [actor (actor-for node pid)] ;; if actor doesn't exist, ignore
          (a/! actor message))
        (if-let [connection (get-connection node other-node)]
          (p/send-message connection pid message)
          (do (timbre/debug
               (format "Couldn't find connection for %s. Please double check this."
                       other-node-name)))))))

  ;; equivalent to {to, 'name@host'} ! message
  (send-registered-message [node from to other-node message]
    ;; check other-node first, if local, check registered actor
    (if (= (util/plain-name other-node) (util/plain-name node))
      (if-let [pid (whereis node to)]
        (do (timbre/debug "Sending " message " to: " to " -- " (actor-for node pid))
            (a/! (actor-for node pid) message))
        (do (timbre/debug "WARNING: couldn't find local actor " to)))
      (if-let [connection (get-connection node other-node)]
        (p/send-reg-message connection from to message)
        (do (timbre/debug
             (format "Couldn't find connection for %s. Please double check this."
                     other-node))))))

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

  ;; should have a reaper thread/actor that periodically traverses the list of
  ;; tracked actors, and claims those that are done/dead.
  (track-pid [node pid actor]
    (timbre/debug "Tracking: {" pid " " actor "}")
    ;; should use refs here to coordinate both changes? I reckon 2 atoms should
    ;; be fine.
    (dosync
     (alter actor-tracker assoc pid actor)
     (alter reverse-actor-tracker assoc actor pid))
    pid)

  (actor-for [node pid]
    (get @actor-tracker pid))

  (pid-for [node actor]
    (get @reverse-actor-tracker actor))

  (self [node]
    (if-let [pid (pid-for node @a/self)]
        pid (recur)))

  (spawn [node f]
    (let [p (pid node)]
      ;; it's likely that there's a race condition here between the spawning
      ;; of the process and it being registered. An actor might want to
      ;; immediately perform node ops that depend on the registry and its own
      ;; registered pid and they won't be available, etc. Figure out how to
      ;; spawn an actor in a "paused" mode
      (track-pid node p (a/spawn f))))

  ;; this is likely to suffer from a race condition just like track-pid
  ;; in particular because of the use of whereis, we probably need a CAS
  ;; type approach here
  (register [node pid name]
    (when (and name
               (not (whereis node name)))
      (swap! actor-registry assoc name pid)
      (timbre/debug "Registering " pid " as " name)
      name))

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

(defn node [name cookie handlers]
  (let [[node-name host] (util/maybe-split name)]
    (timbre/debug node-name "::" host)
    (Node. node-name (or host "localhost") cookie handlers (atom {})
           (ref {:pid 0 :serial 0 :creation 0})
           (ref {:creation 0 :id [0 1 1]})
           (ref {})
           (ref {})
           (atom {}))))
