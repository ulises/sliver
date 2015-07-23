(ns sliver.protocol
  (:require [borges.decoder :refer [decode]]
            [borges.encoder :refer [encode]]
            [bytebuffer.buff :refer [take-ubyte take-uint]]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as timbre])
  (:import [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]))

(defn- tick? [packet]
  (if-not (and (= 4 (.remaining ^ByteBuffer packet))
               (every? zero? (repeatedly 4 #(take-ubyte packet))))
    (do (.rewind ^ByteBuffer packet) false) true))

(def ^ByteBuffer tock (util/flip-pack 4 "i" [0]))

(defn read-pass-through-packet
  [packet]
  (let [_len (take-uint packet)]
    (when (= 112 (take-ubyte packet))
      packet)))

(defn do-loop [^SocketChannel conn handler-fn]
  (let [packet (tcp/read-connected-packet conn)]
    (if (tick? packet) (do (timbre/debug :tock)
                           (tcp/send-bytes conn tock)
                           (.rewind tock))
        (let [pt-packet (read-pass-through-packet packet)
              control   (decode pt-packet)
              message   (decode pt-packet)]
          (handler-fn [control message])))
    (recur conn handler-fn)))

(defn pass-through-message
  [control message]
  (let [enc-control (encode control)
        enc-message (encode message)
        payload (tcp/concat-buffers enc-control enc-message)
        header (util/flip-pack 5 "ib" [(inc (.remaining ^ByteBuffer payload))
                                       112])]
    (tcp/concat-buffers header payload)))

(defn send-message
  [^SocketChannel connection pid message]
  (timbre/debug
   (format "Sent %s bytes"
           (tcp/send-bytes connection
                           (pass-through-message [2 (symbol "") pid]
                                                 message)))))

(defn send-reg-message
  [^SocketChannel connection from-pid to message]
  (timbre/debug
   (format "Sent %s bytes"
           (tcp/send-bytes connection
                           (pass-through-message [6 from-pid (symbol "") to]
                                                 message)))))

(defn parse-control
  [control]
  (condp = (first control)
    2 [nil (last control)]
    6 [(second control) (last control)]))
