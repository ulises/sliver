(ns erlang-node-simulator.epmd
  (:require [bytebuffer.buff :refer [take-ubyte]]
            [erlang-node-simulator.tcp :as tcp]
            [erlang-node-simulator.util :as util]))

(defn alive2-req
  [^String name port]
  (let [name-len   (count name)
        packet-len (+ name-len 13)]
    (util/flip-pack (+ 2 packet-len)
                    (str "sbsbbsss" (apply str (repeat name-len "b")) "s")
                    (concat [packet-len 120 port 77 0 5 5 name-len]
                            (.getBytes name) [0]))))

(defn alive2-resp
  [packet]
  (when (= 121 (take-ubyte packet))
    (if (zero? (take-ubyte packet)) :ok :error)))

(defn register [conn name port]
  (tcp/send-bytes conn (alive2-req name port))
  (alive2-resp (tcp/read-handshake-packet conn)))
