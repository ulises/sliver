(ns erlang-node-simulator.epmd
  (:require [erlang-node-simulator.util :as util]))

(defn alive2-req
  [^String name port]
  (let [name-len   (count name)
        packet-len (+ name-len 13)]
    (util/flip-pack (+ 2 packet-len)
                    (str "sbsbbsss" (apply str (repeat name-len "b")) "s")
                    (concat [packet-len 120 port 77 0 5 5 name-len]
                            (.getBytes name) [0]))))
