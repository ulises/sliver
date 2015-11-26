(ns sliver.epmd
  (:require [bytebuffer.buff :refer [take-ubyte take-ushort]]
            [co.paralleluniverse.pulsar.actors :as a]
            [sliver.node-interface :as ni]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as log])
  (:import [co.paralleluniverse.fibers.io FiberSocketChannel]))

(def PORT 4369)

(defn client []
  (tcp/client "localhost" PORT))

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
  {:status (alive2-resp (tcp/read-handshake-packet conn))
   :connection conn})

(defn port2-req [^String name]
  (let [len (count name)]
    (util/flip-pack (+ 3 len) (str "sb" (apply str (repeat len "b")))
                    (concat [(inc len) 122] (.getBytes name)))))

(defn port2-resp
  [packet]
  (when (= 119 (take-ubyte packet))
    (when-let [result (take-ubyte packet)]
      (take-ushort packet))))

(defn port [conn name]
  (when name
    (tcp/send-bytes conn (port2-req name))
    (port2-resp (tcp/read-handshake-packet conn))))

(defn epmd-handler [node name port wait-for-epmd]
  (ni/spawn node
            (fn []
              (let [epmd-conn   (client)
                    epmd-result (register epmd-conn name port)]
                (if (not (= :ok (:status epmd-result)))
                  (log/debug "Error registering with EPMD:"
                             name " -> " epmd-result))
                (ni/register node 'epmd-socket (ni/self node))
                (util/register-shutdown node 'epmd-socket)
                (deliver wait-for-epmd :ok)
                (a/receive :shutdown
                           (do (log/debug
                                (format "%s: closing epmd connection "
                                        (util/plain-name node)))
                               (.close ^FiberSocketChannel epmd-conn)))))))
