(ns erlang-node-simulator.handshake
  (:require [erlang-node-simulator.util :as util]))

(defn send-name [^String name]
  (let [bytes (concat [(byte \n) 0 5 0 3 0x7f 0xfd]
                      (.getBytes name))
        len   (count bytes)]
    (util/flip-pack (+ 2 len)
                    (str "s" (apply str (repeat len "b")))
                    (concat [len] bytes))))
