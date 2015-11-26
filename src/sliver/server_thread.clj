(ns sliver.server-thread
  (:require [sliver.handshake :as h]
            [sliver.node-interface :as ni]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as log]))

(defn server-thread [node host port wait-for-server]
  (ni/spawn node
            (fn []
              (let [server (tcp/server host port)]
                (ni/register node 'server (ni/self node))
                (util/register-shutdown node 'server)
                (deliver wait-for-server :ok)
                (loop []
                  (log/debug (format "%s: Accepting connections on %s"
                              name port))
                  (let [conn (.accept server)]
                    (log/debug "Accepted connection:" conn)
                    (let [{:keys [status connection other-node]}
                          (h/handle-handshake node conn)]
                      (if (= :ok status)
                        (do (log/debug "Connection established. Saving to:"
                                          other-node)
                            (ni/handle-connection node connection other-node))
                        (log/debug "Handshake failed :("))))
                  (recur))))))
