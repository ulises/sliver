(ns sliver.core
  (:require [sliver.node :as n]
            [sliver.node-interface :as ni]
            [taoensso.timbre :as log]))

(def node (atom nil))

(defn log-handler [node from to msg]
  (log/debug "FROM:" from)
  (log/debug "TO:" to)
  (log/debug "MSG:" msg))

(defn run []
  (when @node
    (ni/stop @node))
  (reset! node (n/node "bar@127.0.0.1" "monster" [#'log-handler]))
  (ni/start @node))
