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

(defrecord Node [name cookie connections]
  NodeP
  (connect [{:keys [name cookie] :as node}
            {:keys [host port] :or {host "localhost"}
             :as other-node}]
    (let [port       (or port
                         (with-open [^SocketChannel epmd-conn
                                     (tcp/client "localhost" 4369)]
                           (epmd/port epmd-conn (:name other-node))))
          connection (tcp/client host port)]
      (h/send-name connection name)
      (h/recv-status connection)        ; should check status is ok,
                                        ; but not just now
      (let [b-challenge (h/recv-challenge connection)
            a-challenge (h/gen-challenge b-challenge cookie)
            _           (h/send-challenge connection a-challenge)
            ack         (h/check-challenge-ack connection
                                               (:challenge a-challenge)
                                               cookie)]
        (if (= :ok ack)
          (swap! (:connections node)
                 assoc other-node connection))
        ack))))

(defn node [name cookie]
  (Node. name cookie (atom {})))
