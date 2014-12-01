(ns sliver.node
  (:require [bytebuffer.buff :refer [take-ubyte]]
            [sliver.handshake :as h]
            [sliver.protocol :as p]
            [taoensso.timbre :as timbre])
  (:import [java.nio.channels SocketChannel]))

(defprotocol NodeP
  "A simple protocol for Erlang nodes."
  (connect
    [node other-node]
    [node other-node handlers]
    "Connects to an Erlang node.")

  (stop [node]
    "Stops node. Closes all connections, etc.")

  (send-message [node pid message]
    "Sends a message to the process pid@host.")
  (send-registered-message [node from-pid process-name other-node message]
    "Sends a registered message to the process process-name@other-node."))

(defrecord Node [node-name cookie state]
  NodeP
  (connect [node other-node] (connect node other-node nil))
  (connect [node other-node handlers]
    (let [[ack connection] (h/shake-hands node other-node)]
      (if (= :ok ack)
        (do (swap! state update-in [other-node] assoc :connection connection)
            (future
              (p/do-loop connection
                         (fn handler [[control message]]
                           (let [[from to] (p/parse-control control)]
                             (when handlers
                               (dorun
                                (for [handler handlers]
                                  (handler node from to message))))))))))
      node))

  (stop [node]
    (dorun
     (for [{:keys [connection]} (vals @state)]
       (do (timbre/debug "Closing:" connection)
           (.close ^SocketChannel connection))))
    node)

  ;; pid(0, 42, 0) ! message
  (send-message [node pid message]
    (let [other-node-name (second (re-find #"(\w+)@" (name (:node pid))))
          connection (get-in @state [{:node-name other-node-name} :connection])]
      (if connection
        (p/send-message connection pid message)
        (timbre/info
         (format "Couldn't find connection for %s - %s"
                 other-node-name @state)))))

  ;; equivalent to {to, 'name@host'} ! message
  (send-registered-message [node from to other-node message]
    (let [connection (get-in @state [other-node :connection])]
      (if connection
        (p/send-reg-message connection from to message)
        (timbre/info
         (format "Couldn't find connection for %s - %s"
                 other-node @state))))))

(defn node [name cookie]
  (Node. name cookie (atom {})))
