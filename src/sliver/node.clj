(ns sliver.node
  (:require [bytebuffer.buff :refer [take-ubyte]]
            [sliver.handshake :as h]
            [sliver.protocol :as p]
            [taoensso.timbre :as timbre])
  (:import [java.nio.channels SocketChannel]))

(defprotocol NodeP
  "A simple protocol for Erlang nodes."
  (connect [node other-node]
    "Connects to an Erlang node.")

  (stop [node]
    "Stops node. Closes all connections, etc."))

(defrecord Node [node-name cookie state]
  NodeP
  (connect [node other-node]
    (let [[ack connection] (h/shake-hands node other-node)]
      (if (= :ok ack)
        (do (swap! state update-in [other-node] assoc :connection connection)
            (future
              (p/do-loop connection
                         (fn handler [[control message]]
                           (let [[tag from cookie to] control]
                             (timbre/info
                              (format "From: %s, To: %s, %s"
                                      from to message))))))))
      node))

  (stop [{:keys [state] :as node}]
    (dorun
     (for [{:keys [connection]} (vals @state)]
       (do (timbre/debug "Closing:" connection)
           (.close ^SocketChannel connection))))
    node))

(defn node [name cookie]
  (Node. name cookie (atom {})))
