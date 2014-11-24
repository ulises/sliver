(ns sliver.protocol
  (:require [bytebuffer.buff :refer [take-ubyte take-uint]]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as timbre])
  (:import [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]))

(defn- tick? [packet]
  (if-not (and (= 4 (.remaining ^ByteBuffer packet))
               (every? zero? (repeatedly 4 #(take-ubyte packet))))
    (do (.rewind ^ByteBuffer packet) false) true))

(def tock (util/flip-pack 4 "i" [0]))

(defn read-pass-through-packet
  [packet]
  (let [_len (take-uint packet)]
    (when (= 112 (take-ubyte packet))
      packet)))

(defn do-loop [^SocketChannel conn handler-fn]
  (let [packet (tcp/read-connected-packet conn)]
    (if (tick? packet) (tcp/send-bytes conn tock)
        (handler-fn packet))
    (recur conn handler-fn)))
