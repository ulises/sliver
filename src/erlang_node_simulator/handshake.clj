(ns erlang-node-simulator.handshake
  (:require [bytebuffer.buff :refer [take-short take-ubyte take-uint slice-off]]
            [erlang-node-simulator.util :as util])
  (:import [java.nio ByteBuffer]))

(defn send-name [^String name]
  (let [bytes (concat [(byte \n) 0 5 0 3 0x7f 0xfd]
                      (.getBytes name))
        len   (count bytes)]
    (util/flip-pack (+ 2 len)
                    (str "s" (apply str (repeat len "b")))
                    (concat [len] bytes))))

(defn recv-status
  [^ByteBuffer payload]
  (when (= \s (char (take-ubyte payload)))
    (keyword (apply str (map char (repeatedly (.remaining payload)
                                              #(take-ubyte payload)))))))

(defn recv-challenge
  [^ByteBuffer payload]
  (when (= \n (char (take-ubyte payload)))
    (let [name-len  (- (.remaining payload) 10)
          version   (take-short payload)
          flag      (take-uint payload)
          challenge (take-uint payload)]
      {:version version :flag flag :challenge challenge
       :name (apply
              str
              (map char
                   (repeatedly name-len
                               #(take-ubyte payload))))})))

(defn send-challenge-reply
  [b-challenge cookie]
  (let [tag         (byte \r)
        a-challenge (util/gen-challenge)
        digest      (util/digest b-challenge cookie)
        payload-len 21]
    {:challenge a-challenge
     :payload (util/flip-pack (+ 2 payload-len) ;; 2 bytes for message size
                              (str "sbi" (apply str (repeat 16 "b")))
                              (concat [payload-len tag a-challenge]
                                      digest))}))

(defn recv-challenge-ack
  [challenge ^String cookie ^ByteBuffer payload]
  (when (= \a (char (take-ubyte payload)))
    (let [a-challenge (map (fn [n] (bit-and n 0xff))
                           (util/digest challenge cookie))
          b-challenge (repeatedly 16 #(take-ubyte payload))]
      (if (= a-challenge b-challenge)
        :ok))))

(defn packet
  [^ByteBuffer payload]
  (let [len (take-short payload)]
    (slice-off payload len)))
