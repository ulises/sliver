(ns sliver.io
  (:require [co.paralleluniverse.pulsar.actors :as a]
            [taoensso.timbre :as log]
            [sliver.protocol :as p]
            [sliver.util :as util])
  (:import [co.paralleluniverse.fibers.io FiberSocketChannel]))

(defn reader [node connection handlers other-node]
  (fn []
    (p/do-loop connection
               (fn handler [[control message]]
                 (let [[from to] (p/parse-control control)]
                   (dorun
                    (for [handler handlers]
                      (handler node from to message))))))))

(defn writer [connection other-node]
  (fn []
    (loop []
      (a/receive [m]
                 [:send-msg pid msg]
                 (do (p/send-message connection pid msg)
                     (recur))

                 [:send-reg-msg from to msg]
                 (do (p/send-reg-message connection from to msg)
                     (recur))

                 ;; if the reader dies, we should close
                 ;; everything and finish
                 [:exit _ref _actor throwable]
                 (do (log/debug (format "%s: Reader died."
                                        (util/writer-name
                                         other-node)))
                     (.close ^FiberSocketChannel connection))

                 :shutdown (.close ^FiberSocketChannel connection)
                 :else (do (log/debug "ELSE:" m)
                           (recur))))))
