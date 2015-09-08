(ns sliver.end-to-end-test
  (:require [clojure.test :refer :all]
            [sliver.node :as n]
            [sliver.epmd :as epmd]
            [sliver.test-helpers :as h]
            [sliver.util :as util]
            [taoensso.timbre :as timbre]))

(defn- handler
  [p]
  (fn [node from to message]
    (deliver p message)))

(deftest accept-incoming-connections-test
  (testing "sliver nodes can connect to each other"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [foo-node (n/node "foo@127.0.0.1" "monster" [])
          bar-node (n/node "bar@127.0.0.1" "monster" [])]
      (n/start foo-node)
      (n/connect bar-node foo-node)

      (n/stop foo-node)
      (n/stop bar-node)
      (h/epmd "-kill")))

  (testing "native erlang nodes can connect to sliver nodes"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node       (n/node "bar@127.0.0.1" "monster" [])
          other-node "foo@127.0.0.1"]

      ;; accept incoming connections
      (n/start node)

      ;; connect from native node
      (h/escript "resources/connect-from-native.escript")

      (is (get @(:state node) other-node))
      (n/stop node)

      (h/epmd "-kill")))

  (testing "more than one native erlang node can connect"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node     (n/node "spaz@127.0.0.1" "monster" [])
          foo-node "foo@127.0.0.1"
          bar-node "bar@127.0.0.1"]

      ;; accept incoming connections
      (n/start node)

      ;; connect from native node foo
      (h/escript "resources/connect-from-native-node-foo.escript")

      ;; ;; connect from native node bar
      (h/escript "resources/connect-from-native-node-bar.escript")

      (is (= (keys @(:state node)) [ bar-node foo-node :epmd-socket
                                    :server-socket]))
      (n/stop node)

      (h/epmd "-kill")))

  (testing "sucessful connections are kept around"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [foo-name "foo@127.0.0.1"
          bar-name "bar@127.0.0.1"
          foo-node (n/node foo-name "monster" [])
          bar-node (n/node bar-name "monster" [])]
      (n/start foo-node)
      (n/connect bar-node foo-node)

      (is (= [bar-name :epmd-socket :server-socket]
             (keys @(:state foo-node))))

      (n/stop foo-node)
      (n/stop bar-node)

      (h/epmd "-kill"))))

(deftest nodes-register-with-epmd
  (testing "nodes register with epmd on start"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [node-name  "foo@127.0.0.1"
          plain-name (util/plain-name "foo")
          node       (n/node node-name "monster" [])]

      (n/start node)
      (is (pos? (epmd/port (epmd/client) plain-name)))
      (n/stop node)

      (h/epmd "-kill")))

  (testing "nodes deregister with epmd on stop"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [node-name "foo@127.0.0.1"
          node      (n/node node-name "monster" [])]
      (n/start node)
      (n/stop node)
      (is (zero? (epmd/port (epmd/client) node-name)))

      (h/epmd "-kill"))))

(deftest ping-pong-test
  (testing "sliver -connect-> native"
    (h/epmd "-daemon" "-relaxed_command_check")
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

      (is (= (deref message-received 100 'fail) message))
      (n/stop node)

      (h/epmd "-kill")))

  (testing "native -connect-> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [message-received (promise)
          other-node       "foo@127.0.0.1"
          node             (n/start (n/node "bar@127.0.0.1" "monster"
                                            [(handler message-received)]))
          pid              (n/pid node)
          message          'ohai2u]

      (h/escript "resources/native-to-sliver.echo-server.escript")

      (n/send-registered-message node pid 'echo other-node
                                 [pid message])

      (is (= (deref message-received 100 'fail) message))
      (n/stop node)

      (h/epmd "-kill"))))

(deftest test-on-demand-ping-pong
  (h/epmd "-daemon" "-relaxed_command_check")
  (let [message-received (promise)
        _                (h/escript "resources/echo-server.escript")
        other-node       {:node-name "foo"}
        node             (n/node "bar@127.0.0.1" "monster"
                                 [(handler message-received)])
        pid              (n/pid node)
        message          'ohai2u]
    (n/send-registered-message node pid 'echo other-node
                               [pid message])

    (is (= (deref message-received 100 'fail) message))
    (n/stop node)
    (h/epmd "-kill")))
