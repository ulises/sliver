(ns sliver.node
  (:require [bytebuffer.buff :refer [take-ubyte]]
            [clojure.pprint :refer [pprint]]
            [sliver.epmd :as epmd]
            [sliver.handshake :as h]
            [sliver.tcp :as tcp]
            [taoensso.timbre :as timbre])
  (:import [java.nio.channels SocketChannel]))

(defn- read-handshake-packet
  [conn handler & debug]
  (let [packet    (tcp/read-handshake-packet conn)
        hs-packet (h/packet packet)
        decoded   (handler hs-packet)]
    (when debug
      (timbre/info "PACKET:" packet)
      (timbre/info "HS-PACKET:" hs-packet)
      (timbre/info "DECODED: " decoded))
    decoded))

(defn- send-name [connection name]
  (tcp/send-bytes connection (h/send-name name)))

(defn- recv-status [connection]
  (read-handshake-packet connection h/recv-status))

(defn- recv-challenge [connection]
  (read-handshake-packet connection h/recv-challenge))

(defn- gen-challenge [b-challenge cookie]
  (h/send-challenge-reply (:challenge b-challenge) cookie))

(defn- send-challenge [connection {:keys [payload] :as challenge}]
  (tcp/send-bytes connection payload))

(defn- check-challenge-ack [connection challenge cookie]
  (read-handshake-packet connection
                         (partial h/recv-challenge-ack challenge cookie)))

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
      (send-name connection name)
      (recv-status connection)          ; should check status is ok, but not
                                        ; just now
      (let [b-challenge (recv-challenge connection)
            a-challenge (gen-challenge b-challenge cookie)
            _           (send-challenge connection a-challenge)
            ack         (check-challenge-ack connection
                                             (:challenge a-challenge)
                                             cookie)]
        (if (= :ok ack)
          (swap! (:connections node)
                 assoc other-node connection))
        ack))))

(defn node [name cookie]
  (Node. name cookie (atom {})))
