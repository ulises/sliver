(ns sliver.node
  (:require [co.paralleluniverse.pulsar.core :as c]
            [sliver.handshake :as h]
            [sliver.handler :as ha]
            [sliver.primitive :as p]
            [sliver.reaper :as r]
            [sliver.server-thread :as s]
            [sliver.util :as util]
            [taoensso.timbre :as timbre]))

(defrecord Node [node-name host cookie handlers state pid-tracker ref-tracker
                 actor-tracker reverse-actor-tracker actor-registry])

(defn #^{:rebind true :dynamic true}
  connect [node other-node]
  (c/join
   (p/actor-for
    node
    (p/spawn node
           #(let [{:keys [status connection]} (h/initiate-handshake node other-node)]
              (if (= :ok status)
                (p/handle-connection node connection other-node))
              node)))))

(defn #^{:rebind true :dynamic true}
  start [{:keys [host] :as node}]
  (let [name            (util/plain-name node)
        port            (+ 1024 (rand-int 50000))
        wait-for-server (c/promise)
        server-thread   (s/server-thread node host port wait-for-server)
        wait-for-epmd   (c/promise)
        epmd-handler    (ha/epmd-handler node name port wait-for-epmd)]
    @wait-for-epmd
    @wait-for-server
    node))

(defn #^{:rebind true :dynamic true}
  stop [{:keys [state] :as node}]
  (timbre/debug (format "%s: stopping..." (util/plain-name node)))
  (dorun
   (for [writer (:shutdown-notify @state)]
     (do (timbre/debug "Notifying:" writer)
         (p/! node writer :shutdown))))
  node)

(defn node
  ([name cookie] (node name cookie [ha/handle-messages]))
  ([name cookie handlers]
   (let [reaper-ready     (c/promise)
         [node-name host] (util/maybe-split name)
         node             (Node. node-name (or host "localhost") cookie handlers
                                 (atom {:shutdown-notify #{}})
                                 (ref {:pid 0 :serial 0 :creation 0})
                                 (ref {:creation 0 :id [0 1 1]})
                                 (ref {})
                                 (ref {})
                                 (atom {}))]
     (timbre/debug node-name "::" host)
     (p/spawn node (r/reaper node reaper-ready))
     @reaper-ready
     node)))
