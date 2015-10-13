(ns sliver.node-test
  (:require [clojure.test :refer :all]
            [sliver.node :as n]
            [co.paralleluniverse.pulsar.actors :as a]
            [sliver.test-helpers :as h]))

(defn- epmd-erl-fixture [f]
  (h/epmd "-daemon" "-relaxed_command_check")
  (h/erl "foo@127.0.0.1" "monster")
  (h/erl "foo2@127.0.0.1" "monster")

  (Thread/sleep 1000)

  (f)

  (h/killall "beam.smp")
  (h/epmd "-kill"))

(use-fixtures :each epmd-erl-fixture)

(deftest test-node-name-connect
  (testing "connect using node name"
    (let [node     (n/node "bar@127.0.0.1" "monster" [])
          foo-node (n/node "foo@127.0.0.1" "monster" [])]
      (is @(:state (n/connect node foo-node)))
      (n/stop node))))

(deftest test-node-connects-to-multiple-erlang-nodes
  (testing "connect using node name"
    (let [node (n/node "bar@127.0.0.1" "monster" [])
          foo  (n/node "foo@127.0.0.1" "monster" [])
          foo2 (n/node "foo2@127.0.0.1" "monster" [])]
      (n/connect node foo)
      (n/connect node foo2)

      (is (= '("foo2" "foo")
             (keys @(:state node))))

      (n/stop node))))

(deftest test-multiple-nodes-can-coexist
  (testing "connecting from several nodes to same erlang node"
    (let [node1 (n/node "bar@127.0.0.1" "monster" [])
          node2 (n/node "baz@127.0.0.1" "monster" [])
          foo   (n/node "foo@127.0.0.1" "monster" [])]
      (n/connect node1 foo)
      (n/connect node2 foo)

      (is (= '("foo")
             (keys @(:state node1))))
      (is (= '("foo")
             (keys @(:state node2))))

      (n/stop node1)
      (n/stop node2))))

(deftest test-pid-minting
  (testing "creating a new pid increments the pid count"
    (let [node (n/node "bar@127.0.0.1" "monster" [])]
      (is (< (:pid (n/pid node)) (:pid (n/pid node))))
      (n/stop node)))

  (testing "creating too many pids rolls pid counter over"
    (let [node (n/node "bar@127.0.0.1" "monster" [])]
      (let [a-pid (n/pid node)]
        (doseq [_ (range 0xfffff)]
          (n/pid node))
        (is (< (:serial a-pid) (:serial (n/pid node))))
        (n/stop node)))))

(deftest test-making-references
  (testing "creating a reference"
    (let [node      (n/node "bar@127.0.0.1" "monster" [])
          pid       (n/pid node)
          reference (n/make-ref node pid)]
      (is "bar@127.0.0.1" (:node reference))
      (is pid (:pid reference)))))

(deftest spawn-test
  (testing "Spawning a process returns a pid"
    (let [node (n/node "bar" "monster" [])]
      (is (= borges.type.Pid (type (n/spawn node #(+ 1 1)))))))

  (testing "Spawned actor is tracked"
    (let [node  (n/node "bar" "monster" [])
          pid   (n/spawn node #(+ 1 1))
          actor (n/actor-for node pid)]
      (is (n/actor-for node pid))
      (is (= pid (n/pid-for node actor)))))

  (testing "Spawned actor actually does work"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          _ (n/spawn node #(deliver result 'did-it))]
      (is (= 'did-it (deref result 100 'didnt-do-it))))))

(deftest send-messages-to-local-processes-test

  (testing "nil pid does not break everything"
    (let [node (n/node "bar" "monster" [])]
      (n/send-message node nil 'hai)
      (is true)))

  (testing "local message doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (promise)
            node   (n/node "bar" "monster" [])
            pid1   (n/spawn node (fn []
                                   (a/receive
                                    m (deliver result m))))]
        (n/send-message node pid1 'success)

        (is (= 'success (deref result 100 'failed))))))

  (testing "local message to non-existing process doesn't kill everything"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [node   (n/node "bar" "monster" [])]
        ;; send to non-existing process
        (n/send-message node (n/pid node) 'success)
        ;; only to get an assertion here
        (is true))))

  (testing "local ping pong doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (promise)
            node   (n/node "bar" "monster" [])
            pid1   (n/spawn node (fn []
                                   (a/receive
                                    [from m] (n/send-message node from m))))]
        (n/spawn node (fn []
                        (n/send-message node pid1 [(n/self node)
                                                   'ping])
                        (a/receive
                         'ping (deliver result 'success))))

        ;; because race condition between actors ping-ponging and assertion
        (Thread/sleep 1000)

        (is (= 'success (deref result 100 'failed)))))))

(deftest self-test
  (testing "self returns own pid"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          pid1   (n/spawn node #(deliver result (n/self node)))]
      (is (= pid1 (deref result 100 'fail))))))

(deftest register-named-processes-test
  (testing "can register a process with a name (symbol)"
    (let [node       (n/node "bar" "monster" [])
          name       'clint-eastwood
          pid        (n/spawn node #(+ 1 1))
          actor-name (n/register node pid name)]
      (is (= name actor-name))))

  (testing "can register a process with a name (keyword)"
    (let [node       (n/node "bar" "monster" [])
          name       :clint-eastwood
          pid        (n/spawn node #(+ 1 1))
          actor-name (n/register node pid name)]
      (is (= name actor-name))))

  (testing "can register a process with a name (string)"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (n/spawn node #(+ 1 1))
          actor-name (n/register node pid name)]
      (is (= name actor-name))))

  (testing "registering a process with an already registered name fails"
    (let [node        (n/node "bar" "monster" [])
          name        'clint-eastwood
          pid         (n/spawn node #(+ 1 1))
          pid2        (n/spawn node #(+ 1 1))
          actor-name1 (n/register node pid name)
          actor-name2 (n/register node pid2 name)]
      (is (= name actor-name1))
      (is (nil? actor-name2))))

  (testing "registering a process with an already registered name fails (many)"
    (let [node        (n/node "bar" "monster" [])
          name        'clint-eastwood
          successful  (atom 0)
          failed      (atom 0)
          actor-fn    (fn []
                        (if-let [name (n/register node (n/self node) name)]
                          (swap! successful inc)
                          (swap! failed inc)))]
      (doall
       (for [_ (range 100000)]
         (n/spawn node actor-fn)))

      (is (and (= 1 @successful)
               (= 99999 @failed)))))

  (testing "can't register with nil name"
    (let [node       (n/node "bar" "monster" [])
          name       nil
          pid        (n/spawn node #(+ 1 1))
          actor-name (n/register node pid name)]
      (is (nil? actor-name))
      (is (nil? (n/whereis node name)))))

  ;; test for registering with anything else -> fail
  ;; in erlang valid names are just atoms, however global
  ;; (https://github.com/erlang/otp/blob/maint/lib/kernel/src/global.erl)
  ;; allows anything to be a name. Why not do it from the get-go here?

  (testing "can find an actor based on its name (symbol)"
    (let [node       (n/node "bar" "monster" [])
          name       'clint-eastwood
          pid        (n/spawn node #(+ 1 1))
          actor-name (n/register node pid name)]
      (is (= pid (n/whereis node actor-name)))))

  (testing "can find an actor based on its name (keyword)"
    (let [node       (n/node "bar" "monster" [])
          name       :clint-eastwood
          pid        (n/spawn node #(+ 1 1))
          actor-name (n/register node pid name)]
      (is (= pid (n/whereis node actor-name)))))

  (testing "can find an actor based on its name (string)"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (n/spawn node #(+ 1 1))
          actor-name (n/register node pid name)]
      (is (= pid (n/whereis node actor-name))))))

(deftest send-messages-to-local-registered-processes-test
  (testing "local message doesn't hit the wire"
    (with-redefs [sliver.protocol/send-reg-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (promise)
            node   (n/node "bar" "monster" [])
            pid1   (n/spawn node (fn []
                                   (a/receive
                                    m (deliver result m))))
            a-name (n/register node pid1 'actor)]

        (Thread/sleep 1000)

        (n/send-registered-message node 'ignored-pid 'actor "bar@127.0.0.1"
                                   'success)

        (is (= 'success (deref result 100 'failed))))))

  (testing "local message to non-existing process doesn't kill everything"
    (with-redefs [sliver.protocol/send-reg-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [node   (n/node "bar" "monster" [])]
        ;; send to non-existing process
        (n/send-registered-message node (n/pid node) 'foobar "bar@127.0.0.1"
                                   'success)

        ;; only to get an assertion here
        (is true))))

  (testing "non-local message should hit the wire"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [messages-sent (atom 0)]
      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (reset! messages-sent 1))]
        (let [bar (n/node "bar" "monster" [])
              foo (n/node "foo" "monster" [])]

          (n/start foo)
          (n/connect bar foo)

          (n/send-registered-message bar (n/pid bar) 'actor "foo@127.0.0.1"
                                     'success)
          (is (= 1 @messages-sent))

          (n/stop foo))))

    (h/epmd "-kill"))

  (testing "non-local message should hit the wire even if there's a local process"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [messages-sent (atom 0)]
      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (reset! messages-sent 1))]
        (let [bar (n/node "bar" "monster" [])
              foo (n/node "foo" "monster" [])
              _   (n/register bar (n/spawn bar #(+ 1 1)) 'actor)]

          (n/start foo)
          (n/connect bar foo)

          (n/send-registered-message bar (n/pid bar) 'actor "foo@127.0.0.1"
                                     'success)
          (is (= 1 @messages-sent))

          (n/stop foo))))
    (h/epmd "-kill"))

  (testing "local registered ping pong doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (atom 0)
            node   (n/node "bar" "monster" [])
            pid1   (n/spawn node (fn []
                                   (n/register node (n/self node) 'pong)
                                   (a/receive
                                    'ping (n/send-registered-message node
                                                                     'ignored
                                                                     'ping
                                                                     "bar@127.0.0.1"
                                                                     'pong))))]
        (n/spawn node (fn []
                        (n/register node (n/self node) 'ping)
                        (n/send-registered-message node 'ignored 'pong
                                                   "bar@127.0.0.1" 'ping)
                        (a/receive
                         'pong (reset! result 1))))

        ;; because race condition between actors ping-ponging and assertion
        (Thread/sleep 1000)

        (is (= 1 @result))))))

(deftest !-send-dwim-test
  (testing "! sends to local actor"
    (let [test-fn
          (fn [name]
            (let [result      (promise)
                  registered? (promise)
                  node        (n/node "bar" "monster" [])
                  _pid        (n/spawn node (fn []
                                              (n/register node (n/self node) name)
                                              (deliver registered? true)
                                              (a/receive 'hai (deliver result true))))]
              @registered?

              ;; sends message to locally registered actor
              (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                               (is false
                                                                   "should not hit the wire"))
                            sliver.protocol/send-reg-message (fn [& _]
                                                               (is false
                                                                   "should not hit the wire"))]
                (n/! node name 'hai))

              (is (deref result 100 false))))]

      (dorun
       (for [name ['actor :actor "actor"]]
         (test-fn name)))))

  (testing "! doesn't barf if local actor doesn't exist"
    (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))
                  sliver.protocol/send-reg-message (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))]
      (let [node (n/node "bar" "monster" [])]
        (n/! node 'actor 'hai)
        (is true))))

  (testing "! doesn't barf if actor is nil"
    (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))
                  sliver.protocol/send-reg-message (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))]
      (let [node (n/node "bar" "monster" [])]
        (n/! node nil 'hai)
        (is true))))

  (testing "! sends to local pid"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          pid    (n/spawn node (fn []
                                 (a/receive _ (deliver result true))))]
      (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                       (is false
                                                           "should not hit the wire"))
                    sliver.protocol/send-reg-message (fn [& _]
                                                       (is false
                                                           "should not hit the wire"))]
        (n/! node pid 'hai))
      (is (deref result 100 false))))

  (testing "! sends to remote pid"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [result (promise)
          bar    (n/node "bar" "monster" [])
          foo    (n/node "foo" "monster" [])
          pid    (n/pid foo)]
      (n/start foo)
      (n/connect bar foo)

      (with-redefs [sliver.protocol/send-message (fn [_conn p m]
                                                   (if (= p pid)
                                                     (deliver result true)))]
        (n/! bar pid 'hai))

      (is (deref result 100 false))

      (n/stop foo))
    (h/epmd "-kill"))

  (testing "! sends to remote actor"
    (let [test-fn (fn [name]
                    (h/epmd "-daemon" "-relaxed_command_check")
                    (let [result (promise)
                          bar    (n/node "bar" "monster" [])
                          foo    (n/node "foo" "monster" [])]
                      (n/start foo)
                      (n/connect bar foo)

                      ;; need to call from within actor because internally
                      ;; this uses (self node)
                      (n/spawn bar (fn []
                                     (with-redefs [sliver.protocol/send-reg-message
                                                   (fn [_conn from to m]
                                                     (if (= to name)
                                                       (deliver result true)))]
                                       (n/! bar [name "foo@127.0.0.1"] 'hai))))

                      (is (deref result 100 false))

                      (n/stop foo))
                    (h/epmd "-kill"))]
      (dorun
       (for [n ['actor :actor "actor"]]
           (test-fn n))))))
