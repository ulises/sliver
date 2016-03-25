(ns sliver.end-to-end-test
  (:require [clojure.test :refer :all]
            [co.paralleluniverse.pulsar.actors :as a]
            [co.paralleluniverse.pulsar.core :as c]
            [sliver.handshake :as ha]
            [sliver.primitive :as p]
            [sliver.node :as n]
            [sliver.epmd :as epmd]
            [sliver.test-helpers :as h]
            [sliver.util :as util])
  (:import [co.paralleluniverse.strands Strand]))

(defn- handler
  [p]
  (fn [node from to message]
    (deliver p message)))


(defn- setup []
  (h/epmd "-daemon" "-relaxed_command_check")
  (h/erl "foo@127.0.0.1" "monster")
  (h/erl "foo2@127.0.0.1" "monster")

  ;; give the erlang nodes some time to setup
  (Strand/sleep 1000))

(defn- teardown []
  (h/killall "beam.smp")
  (h/epmd "-kill"))


(deftest test-node-name-connect
  (testing "connect using node name"
    (setup)

    (let [node     (n/node "bar@127.0.0.1" "monster" [])
          foo-node {:node-name "foo@127.0.0.1"}]
      (is @(:state (n/connect node foo-node))))

    (teardown)))

(deftest test-node-connects-to-multiple-erlang-nodes
  (testing "connect using node name"
    (setup)

    (let [node (n/node "bar@127.0.0.1" "monster" [])
          foo  {:node-name "foo@127.0.0.1"}
          foo2 {:node-name "foo2@127.0.0.1"}]

      (n/connect node foo)
      (n/connect node foo2)

      (is (p/whereis node 'foo-writer))
      (is (p/whereis node 'foo2-writer)))

    (teardown)))

(deftest test-multiple-nodes-can-coexist
  (testing "connecting from several nodes to same erlang node"
    (setup)

    (let [node1 (n/node "bar@127.0.0.1" "monster" [])
          node2 (n/node "baz@127.0.0.1" "monster" [])
          foo   (n/node "foo@127.0.0.1" "monster" [])]
      (n/connect node1 foo)
      (n/connect node2 foo)

      (is (p/whereis node1 'foo-writer))
      (is (p/whereis node2 'foo-writer)))

    (teardown)))

(deftest accept-incoming-connections-test
  (testing "sliver nodes can connect to each other"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [foo-node (n/node "foo@127.0.0.1" "monster" [])
          bar-node (n/node "bar@127.0.0.1" "monster" [])]
      (n/start foo-node)
      (n/connect bar-node foo-node)

      (is (p/whereis foo-node 'bar-writer))

      (n/stop foo-node)
      (n/stop bar-node)
      (h/epmd "-kill")))

  (testing "native erlang nodes can connect to sliver nodes"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node (n/node "bar@127.0.0.1" "monster" [])]

      ;; accept incoming connections
      (n/start node)
      (Strand/sleep 1000)

      ;; connect from native node foo@127.0.0.1
      (h/escript "resources/connect-from-native.escript")

      (is (p/whereis node 'foo-writer))

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

      (is (p/whereis node 'foo-writer))
      (is (p/whereis node 'bar-writer))

      (n/stop node)
      (h/epmd "-kill"))))

(deftest nodes-register-with-epmd
  (testing "nodes register with epmd on start"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [node-name  "foo@127.0.0.1"
          node       (n/node node-name "monster" [])
          plain-name (util/plain-name node)]

      (n/start node)

      (is (pos? (c/join
                 (a/spawn
                  #(epmd/port (epmd/client) plain-name)))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "nodes deregister with epmd on stop"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [node-name  "foo@127.0.0.1"
          node       (n/node node-name "monster" [])
          plain-name (util/plain-name node)]

      (n/start node)

      ;; check node registered
      (is (pos? (c/join
                 (a/spawn
                  #(epmd/port (epmd/client) plain-name)))))

      (n/stop node)

      (is (zero? (c/join
                  (a/spawn
                   #(epmd/port (epmd/client) plain-name)))))

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
          pid              (p/pid node)
          message          'ohai2u]

      (a/spawn #(p/send-registered-message node pid 'echo other-node
                                           [pid message]))

      (is (= (deref message-received 100 'fail) message))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "native -connect-> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [message-received (promise)
          other-node       "foo@127.0.0.1"
          node             (n/start (n/node "bar@127.0.0.1" "monster"
                                            [(handler message-received)]))
          pid              (p/pid node)
          message          'ohai2u]

      (h/escript "resources/native-to-sliver.echo-server.escript")

      (a/spawn #(p/send-registered-message node pid 'echo other-node
                                           [pid message]))

      (is (= (deref message-received 100 'fail) message))

      (n/stop node)
      (h/epmd "-kill"))))

(defn- type-x-echo-test [data]
  (let [message-received (promise)
        other-node       "foo@127.0.0.1"
        node             (n/start (n/node "bar@127.0.0.1" "monster"
                                          [(handler message-received)]))
        pid              (p/pid node)
        message          data]

    (h/escript "resources/native-to-sliver.echo-server.escript")

    (Strand/sleep 1000)

    (a/spawn #(p/send-registered-message node pid 'echo other-node
                                         [pid message]))

    (let [expected (deref message-received 5000 'fail)]
      (is (= expected message)
          (str "T-ex:" (type expected) " -- "
               "T-ac:" (type message))))
    (n/stop node)))

(deftest all-types-echo-test
  (h/epmd "-daemon" "-relaxed_command_check")
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
    (type-x-echo-test t))
  (h/epmd "-kill"))

(deftest native-handshake-test
  (testing "successful handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (h/erl "bar@127.0.0.1" cookie)

      (Strand/sleep 1000)

      ;; connect nodes
      (is (= :ok (c/join (a/spawn
                          #(:status (ha/initiate-handshake node bar-node))))))

      (h/killall "beam.smp")
      (h/epmd "-kill")))

  (testing "alive handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (h/erl "bar@127.0.0.1" cookie)

      (Strand/sleep 1000)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn #(:status (ha/initiate-handshake node bar-node))))))

      ;; the old connection is live, this should not return a new connected
      ;; socket.
      (is (= {:status :alive :connection nil}
             (c/join (a/spawn #(ha/initiate-handshake node bar-node)))))

      (h/killall "beam.smp")
      (h/epmd "-kill")))

  (testing "wrong cookies native -connect-> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node (n/node "bar@127.0.0.1" "monster" [])]

      (n/start node)

      (h/escript "resources/wrong.cookie.native->sliver.escript")

      ;; if there's no connection to foo, there's no writer for foo
      (is (nil? (p/whereis node 'foo-writer)))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "wrong cookies sliver -connect-> native"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [foo  "foo@127.0.0.1"
          node (n/node "bar@127.0.0.1" "monster" [])]

      (h/erl foo "random")
      (Strand/sleep 1000)

      (n/connect node {:node-name "foo"})

      ;; the node hasn't been started, so no connections to foo here
      (is (nil? (p/whereis node 'foo-writer)))

      (h/killall "beam.smp")
      (h/epmd "-kill"))))

(deftest sliver-handshake-test
  (testing "successful handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node {:node-name "bar@127.0.0.1" :cookie cookie}]

      (n/start node)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn #(:status (ha/initiate-handshake bar-node node))))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "alive handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "spaz@127.0.0.1" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (n/start node)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn #(:status (ha/initiate-handshake bar-node node))))))

      ;; the old connection is live, this should not return a new connected
      ;; socket.
      (is (= {:status :alive :connection nil}
             (c/join (a/spawn #(ha/initiate-handshake bar-node node)))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "wrong cookies sliver -connect-> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node {:node-name "bar@127.0.0.1" :cookie "random"}]

      (n/start node)

      ;; connect nodes
      (is (= :error (c/join
                     (a/spawn #(:status (ha/initiate-handshake bar-node
                                                               node))))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "wrong cookies sliver -connect-> sliver II"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node     (n/node "foo@127.0.0.1" "monster" [])
          bar-node (n/node  "bar@127.0.0.1" "random" [])]

      (n/start bar-node)

      (n/connect node bar-node)

      ;; wrong cookies, so no connections here
      (is (nil? (p/whereis node 'bar-writer)))

      (n/stop node)
      (h/epmd "-kill"))))

(deftest all-name-variations-work-test
  (testing "foo <-> bar (sliver)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo" cookie [])
          bar-node (n/node "bar" cookie [])]

      (n/start node)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn #(:status (ha/initiate-handshake bar-node node))))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "foo <-> bar (native)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo" cookie [])
          bar-node (n/node "bar" cookie [])]

      (h/erl "bar" cookie)
      (Strand/sleep 1000)

      ;; connect nodes
      (is (= :ok (c/join 
                  (a/spawn #(:status (ha/initiate-handshake node
                                                            bar-node))))))

      (h/killall "beam.smp")
      (h/epmd "-kill")))

  (testing "foo <-> bar@ip (sliver)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (n/start node)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn
                   #(:status (ha/initiate-handshake bar-node node))))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "foo <-> bar@ip (native)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (h/erl "bar@127.0.0.1" cookie)
      (Strand/sleep 1000)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn
                   #(:status (ha/initiate-handshake node bar-node))))))

      (h/killall "beam.smp")
      (h/epmd "-kill")))

  (testing "foo@ip <-> bar (sliver)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node (n/node "bar" cookie [])]

      (n/start node)

      ;; connect nodes

      (is (= :ok (c/join
                  (a/spawn
                   #(:status (ha/initiate-handshake bar-node node))))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "foo@ip <-> bar (native)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node (n/node "bar" cookie [])]

      (h/erl "bar" cookie)
      (Strand/sleep 1000)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn
                   #(:status (ha/initiate-handshake node bar-node))))))

      (h/killall "beam.smp")
      (h/epmd "-kill")))

  (testing "foo@ip <-> bar@ip (sliver)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@localhost" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (n/start node)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn
                   #(:status (ha/initiate-handshake bar-node node))))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "foo@ip <-> bar@ip (native)"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@localhost" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (h/erl "bar@127.0.0.1" cookie)
      (Strand/sleep 1000)

      ;; connect nodes
      (is (= :ok (c/join
                  (a/spawn
                   #(:status (ha/initiate-handshake node bar-node))))))

      (h/killall "beam.smp")
      (h/epmd "-kill"))))

(deftest close-sockets-on-shutdown-test
  (testing "writer sockets are closed on node shutdown"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node (n/node "bar@127.0.0.1" cookie [])]

      (n/start node)

      (n/connect bar-node node)

      ;; nodes are connected after here
      (let [a (a/spawn
               #(do (p/monitor bar-node (p/whereis bar-node 'foo-writer))
                    (a/receive [m]
                               [:exit ref actor throwable] :socket-closed
                               :after 15000 :socket-not-closed)))]
        ;; should close the connections; in turn the foo-writer process in
        ;; bar-node should throw an exception since the socket is closed
        (n/stop node)

        (is (= :socket-closed (c/join a))))

      (h/epmd "-kill"))))
