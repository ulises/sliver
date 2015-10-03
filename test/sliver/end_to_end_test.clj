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
    (timbre/debug from "->" to "::" message)
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
          other-node "foo"]

      ;; accept incoming connections
      (n/start node)

      ;; connect from native node
      (h/escript "resources/connect-from-native.escript")

      (is (get @(:state node) other-node))
      (n/stop node)

      (h/epmd "-kill")))

  (testing "more than one native erlang node can connect"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node (n/node "spaz@127.0.0.1" "monster" [])]

      ;; accept incoming connections
      (n/start node)

      ;; connect from native node foo
      (h/escript "resources/connect-from-native-node-foo.escript")

      ;; ;; connect from native node bar
      (h/escript "resources/connect-from-native-node-bar.escript")

      (is (= (keys @(:state node)) ["bar" "foo" :epmd-socket :server-socket]))
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

      (is (= ["bar" :epmd-socket :server-socket]
             (keys @(:state foo-node))))

      (n/stop foo-node)
      (n/stop bar-node)

      (h/epmd "-kill"))))

(deftest nodes-register-with-epmd
  (testing "nodes register with epmd on start"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [node-name  "foo@127.0.0.1"
          node       (n/node node-name "monster" [])
          plain-name (util/plain-name node)]

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
          other-node       (n/node "foo" "monster" [])
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

(defn- type-x-echo-test [data]
  (h/epmd "-daemon" "-relaxed_command_check")
  (let [message-received (promise)
        other-node       "foo@127.0.0.1"
        node             (n/start (n/node "bar@127.0.0.1" "monster"
                                          [(handler message-received)]))
        pid              (n/pid node)
        message          data]

    (h/escript "resources/native-to-sliver.echo-server.escript")

    (n/send-registered-message node pid 'echo other-node
                               [pid message])

    (let [expected (deref message-received 5000 'fail)]
      (is (= expected message)
          (str "T-ex:" (type expected) " -- "
               "T-ac:" (type message))))
    (n/stop node)

    (h/epmd "-kill")))

(deftest all-types-echo-test
  (doseq [t [(int 1)
             ;; 123.456 ;; This works ok with the exception that we read a
             ;; Double instead of a float
             'foo
             ;; :foo <- decoding this returns a symbol, that's because both
             ;; symbols and keywords encode to an erlang atom :/
             ['foo 'bar "ohais2u" [1000 10001 65535]]
             nil
             "foo"
             '(1 2 3)
             1267650600228229401496703205376
             123456789101112131415161718192021222324252627282930313233343536373839404142434445464748495051525354555657585960616263646566676869707172737475767778798081828384858687888990919293949596979899100101102103104105106107108109110111112113114115116117118119120121122123124125126127128129130131132133134135136137138139140141142143144145146147148149150151152153154155156157158159160161162163164165166167168169170171172173174175176177178179180181182183184185186187188189190191192193194195196197198199200201202203204205206207208209210211212213214215216217218219220221222223224225226227228229230231232233234235236237238239240241242243244245246247248249250251252253254255256
             (borges.type/reference (symbol "nonode@nohost") [0 0 43] 0)
             (borges.type/pid (symbol "nonode@nohost") 41 0 0)
             {"foo" 'bar
              (borges.type/pid (symbol "nonode@nohost") 41 0 0) {1 [1 2 3]}}
             ;; (byte-array [1 2 3]) ;; test fails because comparing byte arrays.
             ;; Data is actually  returned ok
             (seq [1 2 3])
             (map identity [1 2 3])]]
      (type-x-echo-test t)))
