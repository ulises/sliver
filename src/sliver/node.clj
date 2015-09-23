(ns sliver.node
  (:require [borges.type :as t]
            [borges.decoder :as d]
            [bytebuffer.buff :refer [take-ubyte]]
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

  (save-connection [node other-node connection]
    "Saves the connection to other-node")

  (get-connection [node other-node]
    "Gets the socket to other-node")

  (handle-connection [node connection]
    "Handles a connection after the handshake has been successful"))

(defrecord Node [node-name host cookie handlers state pid-tracker]
  NodeP
  (connect [node other-node]
    (let [{:keys [status connection]} (h/do-handshake node other-node)]
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
                              (let [{:keys [status connection other-node]} (h/handle-handshake node conn)]
                                (if (= :ok status)
                                  (do (timbre/debug "Connection established. Saving to " other-node)
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
          other-node      {:node-name other-node-name}
          connection      (get-connection node other-node)]
      (if connection
        (p/send-message connection pid message)
        (do (timbre/debug
             (format "Couldn't find connection for %s. Please double check this."
                     other-node-name))))))

  ;; equivalent to {to, 'name@host'} ! message
  (send-registered-message [node from to other-node message]
    (let [connection (get-connection node other-node)]
      (if connection
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
         (alter pid-tracker assoc :pid next-pid :serial next-serial)
         new-pid)))))

(defn node [name cookie handlers]
  (let [[node-name host] (util/maybe-split name)]
    (timbre/debug node-name "::" host)
    (Node. node-name (or host "localhost") cookie handlers (atom {})
           (ref {:pid 0 :serial 0 :creation 0}))))
