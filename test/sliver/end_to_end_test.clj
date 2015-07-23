(ns sliver.end-to-end-test
  (:require [clojure.test :refer :all]
            [sliver.node :as n]
            [sliver.test-helpers :as h]))

(defn- handler
  [p]
  (fn [node from to message]
    (deliver p message)))

(deftest ping-pong-test
  (let [message-received (promise)
        _                (h/escript "resources/echo-server.escript")
        other-node       {:node-name "foo"}
        node             (n/connect (n/node "bar@127.0.0.1" "monster"
                                            [(handler message-received)])
                                    other-node)
        pid              (n/pid node)
        message          'ohai2u]
    (n/send-registered-message node pid 'echo other-node
                               [pid message])

    (is (= @message-received message))))

(deftest test-on-demand-ping-pong
  (let [message-received (promise)
        _                (h/escript "resources/echo-server.escript")
        other-node       {:node-name "foo"}
        node             (n/node "bar@127.0.0.1" "monster"
                                 [(handler message-received)])
        pid              (n/pid node)
        message          'ohai2u]
    (n/send-registered-message node pid 'echo other-node
                               [pid message])

    (is (= @message-received message))))
