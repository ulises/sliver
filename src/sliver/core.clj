(ns sliver.core
  (:require [sliver.node :as n]
            [sliver.node-interface :as ni]
            [taoensso.timbre :as log]))

(def node (atom nil))

(defn log-handler [node from to msg]
  (try
    (let [gen-call (first msg)
          [pid reference] (second msg)
          [_call module f args] (last msg)
          result (apply (resolve (symbol (str module "/" f))) args)]
      (ni/send-message node pid [reference result]))
    (catch Exception e
      (log/info e))))

(defn run []
  (when @node
    (ni/stop @node))
  (reset! node (n/node "bar@127.0.0.1" "monster" [#'log-handler]))
  (ni/start @node)
  (ni/connect @node {:node-name "foo"}))
