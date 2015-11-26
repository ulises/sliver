(ns sliver.reaper
  (:require [co.paralleluniverse.pulsar.actors :as a]
            [sliver.node-interface :as ni]
            [sliver.util :as u]
            [taoensso.timbre :as log]))

(defn reaper [node reaper-ready]
  (fn []
    (ni/register node '_dead-processes-reaper (ni/self node))
    (u/register-shutdown node '_dead-processes-reaper)
    (deliver reaper-ready :ok)
    (loop []
      (a/receive [m]
                 [:monitor pid] (do (ni/monitor node pid)
                                    (recur))
                 [:exit _ref actor _reason]
                 (let [pid (ni/pid-for node actor)]
                   (ni/untrack node pid)
                   (ni/unregister node (ni/name-for node pid))
                   (recur))
                 [:shutdown] (do (log/debug "Reaper shutting down...")
                                 :ok)))))
