(ns sliver.handler
  (:require [co.paralleluniverse.pulsar.actors :as a]
            [taoensso.timbre :as log]
            [sliver.epmd :as epmd]
            [sliver.primitive :as p]
            [sliver.util :as util])
  (:import [co.paralleluniverse.fibers.io FiberSocketChannel]))

(defn handle-messages [node from to message]
  (log/debug "HANDLER:" from "->" to "::" message)
  (p/! node to message))

(defn epmd-handler [node name port wait-for-epmd]
  (p/spawn node
           (fn []
             (let [epmd-conn   (epmd/client)
                   epmd-result (epmd/register epmd-conn name port)]
               (if (not (= :ok (:status epmd-result)))
                 (log/debug "Error registering with EPMD:" name " -> "
                            epmd-result))
               (p/register node 'epmd-socket (p/self node))
               (util/register-shutdown node 'epmd-socket)
               (deliver wait-for-epmd :ok)
               (a/receive :shutdown
                          (do (log/debug
                               (format "%s: closing epmd connection "
                                       (util/plain-name node)))
                              (.close ^FiberSocketChannel epmd-conn)))))))
