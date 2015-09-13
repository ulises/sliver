(ns sliver.core
  (:require [sliver.node :as n]
            [taoensso.timbre :as log]))

(def node (atom nil))

(defn log-handler [node msg]
  (log/info (reduce str msg)))

(defn run []
  (when @node
    (n/stop @node))
  (reset! node (n/node "bar@127.0.0.1" "monster" [log-handler]))
  (n/start @node))
