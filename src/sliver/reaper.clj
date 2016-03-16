(ns sliver.reaper
  (:require [co.paralleluniverse.pulsar.actors :as a]
            [sliver.primitive :as p]
            [sliver.util :as u]
            [taoensso.timbre :as log]))

(defn reaper [node reaper-ready]
  (fn []
    (p/register node '_dead-processes-reaper (p/self node))
    (u/register-shutdown node '_dead-processes-reaper)
    (deliver reaper-ready :ok)
    (loop []
      (a/receive [m]
                 [:monitor pid] (do (p/monitor node pid)
                                    (recur))
                 [:exit _ref actor _reason]
                 (let [pid (p/pid-for node actor)]
                   (p/untrack node pid)
                   (p/unregister node (p/name-for node pid))
                   (recur))
                 [:shutdown] (do (log/debug "Reaper shutting down...")
                                 :ok)))))
