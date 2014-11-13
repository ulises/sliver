(ns erlang-node-simulator.handshake
  (:require [bytebuffer.buff :refer [take-short take-ubyte]]
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
  ;; packet size - 1 byte for the tag = length of the status
  (let [len (dec (take-short payload))]
    (when (= \s (char (take-ubyte payload)))
      (keyword (apply str (map char (repeatedly len #(take-ubyte payload))))))))
