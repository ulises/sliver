(ns sliver.handler
  (:require [taoensso.timbre :as log]
            [sliver.node-interface :as ni]))

(defn handle-messages [node from to message]
  (log/debug "HANDLER:" from "->" to "::" message)
  (ni/! node to message))
