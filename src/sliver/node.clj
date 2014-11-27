(ns sliver.node
  (:require [bytebuffer.buff :refer [take-ubyte]]
            [clojure.pprint :refer [pprint]]
            [sliver.epmd :as epmd]
            [sliver.handshake :as h]
            [sliver.tcp :as tcp]
            [taoensso.timbre :as timbre])
  (:import [java.nio.channels SocketChannel]))

(defprotocol NodeP
  "A simple protocol for Erlang nodes."
  (connect [node other-node]
    "Connects to an Erlang node."))

(defrecord Node [name cookie state]
  NodeP
  (connect [{:keys [name cookie] :as node}
            {:keys [host port] :or {host "localhost"}
             :as other-node}]
    (let [[ack connection] (h/shake-hands node other-node)]
      (if (= :ok ack)
        (do (swap! state update-in [other-node] assoc :connection connection)
            (future
              (p/do-loop connection
                         (fn handler [raw-packet])))))
      node)))

(defn node [name cookie]
  (Node. name cookie (atom {})))
