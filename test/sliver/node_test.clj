(ns sliver.node-test
  (:require [clojure.test :refer :all]
            [sliver.node :as n]
            [sliver.node-interface :as ni]
            [co.paralleluniverse.pulsar.actors :as a]
            [co.paralleluniverse.pulsar.core :as c]
            [sliver.test-helpers :as h]
            [sliver.util :as util]
            [taoensso.timbre :as log])
  (:import [co.paralleluniverse.strands Strand]))

(defn- epmd-erl-fixture [f]
  (h/epmd "-daemon" "-relaxed_command_check")
  (h/erl "foo@127.0.0.1" "monster")
  (h/erl "foo2@127.0.0.1" "monster")

  (Strand/sleep 1000)

  (f)

  (h/killall "beam.smp")
  (h/epmd "-kill"))

(use-fixtures :each epmd-erl-fixture)

(deftest test-node-name-connect
  (testing "connect using node name"
    (let [node     (n/node "bar@127.0.0.1" "monster" [])
          foo-node (n/node "foo@127.0.0.1" "monster" [])]
      (is @(:state (ni/connect node foo-node))))))

(deftest test-node-connects-to-multiple-erlang-nodes
  (testing "connect using node name"
    (let [node (n/node "bar@127.0.0.1" "monster" [])
          foo  (n/node "foo@127.0.0.1" "monster" [])
          foo2 (n/node "foo2@127.0.0.1" "monster" [])]
      (ni/connect node foo)
      (ni/connect node foo2)

      (is (ni/whereis node 'foo-writer))
      (is (ni/whereis node 'foo2-writer)))))

(deftest test-multiple-nodes-can-coexist
  (testing "connecting from several nodes to same erlang node"
    (let [node1 (n/node "bar@127.0.0.1" "monster" [])
          node2 (n/node "baz@127.0.0.1" "monster" [])
          foo   (n/node "foo@127.0.0.1" "monster" [])]
      (ni/connect node1 foo)
      (ni/connect node2 foo)

      (is (ni/whereis node1 'foo-writer))
      (is (ni/whereis node2 'foo-writer)))))

(deftest test-pid-minting
  (testing "creating a new pid increments the pid count"
    (let [node (n/node "bar@127.0.0.1" "monster" [])]
      (is (< (:pid (ni/pid node)) (:pid (ni/pid node))))
      (ni/stop node)))

  (testing "creating too many pids rolls pid counter over"
    (let [node (n/node "bar@127.0.0.1" "monster" [])]
      (let [a-pid (ni/pid node)]
        (doseq [_ (range 0xfffff)]
          (ni/pid node))
        (is (< (:serial a-pid) (:serial (ni/pid node))))
        (ni/stop node)))))

(deftest test-making-references
  (testing "creating a reference"
    (let [node      (n/node "bar@127.0.0.1" "monster" [])
          pid       (ni/pid node)
          reference (ni/make-ref node pid)]
      (is "bar@127.0.0.1" (:node reference))
      (is pid (:pid reference)))))

(deftest spawn-test
  (testing "Spawning a process returns a pid"
    (let [node (n/node "bar" "monster" [])]
      (is (= borges.type.Pid (type (ni/spawn node #(+ 1 1)))))))

  (testing "Spawned actor is tracked"
    (let [node  (n/node "bar" "monster" [])
          pid   (ni/spawn node #(Strand/sleep 5000))
          actor (ni/actor-for node pid)]
      (is (ni/actor-for node pid))
      (is (= pid (ni/pid-for node actor)))))

  (testing "Spawned actor actually does work"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          _ (ni/spawn node #(deliver result 'did-it))]
      (is (= 'did-it (deref result 100 'didnt-do-it))))))

(deftest send-messages-to-local-processes-test

  (testing "nil pid does not break everything"
    (let [node (n/node "bar" "monster" [])]
      (ni/send-message node nil 'hai)
      (is true)))

  (testing "local message doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (promise)
            node   (n/node "bar" "monster" [])
            pid1   (ni/spawn node (fn []
                                   (a/receive
                                    m (deliver result m))))]
        (ni/send-message node pid1 'success)

        (is (= 'success (deref result 100 'failed))))))

  (testing "local message to non-existing process doesn't kill everything"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [node   (n/node "bar" "monster" [])]
        ;; send to non-existing process
        (ni/send-message node (ni/pid node) 'success)
        ;; only to get an assertion here
        (is true))))

  (testing "local ping pong doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (promise)
            node   (n/node "bar" "monster" [])
            pid1   (ni/spawn node (fn []
                                   (a/receive
                                    [from m] (ni/send-message node from m))))]
        (ni/spawn node (fn []
                        (ni/send-message node pid1 [(ni/self node)
                                                   'ping])
                        (a/receive
                         'ping (deliver result 'success))))

        ;; because race condition between actors ping-ponging and assertion
        (Strand/sleep 1000)

        (is (= 'success (deref result 100 'failed)))))))

(deftest self-test
  (testing "self returns own pid"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          pid1   (ni/spawn node #(deliver result (ni/self node)))]
      (is (= pid1 (deref result 100 'fail))))))

(deftest register-named-processes-test
  (testing "can register a process with a name (symbol)"
    (let [node       (n/node "bar" "monster" [])
          name       'clint-eastwood
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (is (= name actor-name))))

  (testing "can register a process with a name (keyword)"
    (let [node       (n/node "bar" "monster" [])
          name       :clint-eastwood
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (is (= name actor-name))))

  (testing "can register a process with a name (string)"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (is (= name actor-name))))

  (testing "registering a process with an already registered name fails"
    (let [node        (n/node "bar" "monster" [])
          name        'clint-eastwood
          pid         (ni/spawn node #(a/receive))
          pid2        (ni/spawn node #(a/receive))
          actor-name1 (ni/register node name pid)
          actor-name2 (ni/register node name pid2)]
      (is (= name actor-name1))
      (is (nil? actor-name2))))

  (testing "registering a process with an already registered name fails (many)"
    (let [node        (n/node "bar" "monster" [])
          name        'clint-eastwood
          successful  (atom 0)
          failed      (atom 0)
          actor-fn    (fn []
                        (if-let [name (ni/register node name (ni/self node))]
                          (swap! successful inc)
                          (swap! failed inc))
                        (a/receive))]
      (doall
       (for [_ (range 100)]
         (ni/spawn node actor-fn)))

      (Strand/sleep 1000)

      (is (= 1 @successful))
      (is (= 99 @failed))))

  (testing "can't register with nil name"
    (let [node       (n/node "bar" "monster" [])
          name       nil
          pid        (ni/spawn node #(+ 1 1))
          actor-name (ni/register node name pid)]
      (is (nil? actor-name))
      (is (nil? (ni/whereis node name)))))

  ;; test for registering with anything else -> fail
  ;; in erlang valid names are just atoms, however global
  ;; (https://github.com/erlang/otp/blob/maint/lib/kernel/src/global.erl)
  ;; allows anything to be a name. Why not do it from the get-go here?

  (testing "can find an actor based on its name (symbol)"
    (let [node       (n/node "bar" "monster" [])
          name       'clint-eastwood
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (is (= pid (ni/whereis node actor-name)))))

  (testing "can find an actor based on its name (keyword)"
    (let [node       (n/node "bar" "monster" [])
          name       :clint-eastwood
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (is (= pid (ni/whereis node actor-name)))))

  (testing "can find an actor based on its name (string)"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (is (= pid (ni/whereis node actor-name)))))

  (testing "can unregister a registered actor"
    (let [node       (n/node "bar" "monster" [])
          name       'actor
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (ni/unregister node actor-name)
      (is (nil? (ni/whereis node actor-name)))))

  (testing "can find a registered actor's name from its pid"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (ni/spawn node #(a/receive))
          actor-name (ni/register node name pid)]
      (is (= actor-name (ni/name-for node pid))))))

(deftest send-messages-to-local-registered-processes-test
  (testing "local message doesn't hit the wire"
    (let [result (promise)
          node   (n/node "bar" "monster" [])]

      (ni/spawn node (fn []
                       (ni/register node 'actor (ni/self node))
                       (a/receive m (deliver result m))))
      (Strand/sleep 1000)

      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (is false "messages should not hit the wire"))]
        (ni/send-registered-message node 'ignored-pid 'actor "bar@127.0.0.1"
                                    'success))

      (is (= 'success (deref result 1000 'failed)))))

  (testing "local message to non-existing process doesn't kill everything"
    (with-redefs [sliver.protocol/send-reg-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [node   (n/node "bar" "monster" [])]
        ;; send to non-existing process
        (ni/send-registered-message node (ni/pid node) 'foobar "bar@127.0.0.1"
                                   'success)

        ;; only to get an assertion here
        (is true))))

  (testing "non-local message should hit the wire"
    (let [messages-sent (atom 0)]
      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (reset! messages-sent 1))]
        (let [bar  (n/node "bar" "monster" [])
              spaz (n/node "spaz" "monster" [])]

          (ni/start spaz)
          (ni/connect bar spaz)

          (ni/send-registered-message bar (ni/pid bar) 'actor "spaz@127.0.0.1"
                                     'success)
          (is (= 1 @messages-sent))

          (ni/stop spaz)))))

  (testing "non-local message should hit the wire even if there's a local process"
    (let [messages-sent (atom 0)]
      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (reset! messages-sent 1))]
        (let [bar  (n/node "bar" "monster" [])
              spaz (n/node "spaz" "monster" [])
              _    (ni/register bar 'actor
                                (ni/spawn bar #(a/receive m :ok
                                                          :after 5000 :ok)))]

          (ni/start spaz)
          (ni/connect bar spaz)

          (Strand/sleep 1000)

          (ni/send-registered-message bar (ni/pid bar) 'actor "spaz@127.0.0.1"
                                     'success)

          (Strand/sleep 1000)

          (is (= 1 @messages-sent))

          (ni/stop spaz)))))

  (testing "local registered ping pong doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (atom 0)
            node   (n/node "bar" "monster" [])
            pid1   (ni/spawn node (fn []
                                    (ni/register node 'pong (ni/self node))
                                    (a/receive
                                     'ping (ni/send-registered-message node
                                                                       'ignored
                                                                       'ping
                                                                       "bar@127.0.0.1"
                                                                       'pong))))]
        (ni/spawn node (fn []
                         (ni/register node 'ping (ni/self node))
                         (ni/send-registered-message node 'ignored 'pong
                                                     "bar@127.0.0.1" 'ping)
                         (a/receive
                          'pong (reset! result 1))))

        ;; because race condition between actors ping-ponging and assertion
        (Strand/sleep 1000)

        (is (= 1 @result))))))

(deftest !-send-dwim-test
  (testing "! sends to local actor"
    (let [test-fn
          (fn [name]
            (let [result      (promise)
                  registered? (promise)
                  node        (n/node "bar" "monster" [])
                  _pid        (ni/spawn node (fn []
                                               (ni/register node name
                                                            (ni/self node))
                                               (deliver registered? true)
                                               (a/receive 'hai
                                                          (deliver result
                                                                   true))))]
              @registered?

              ;; sends message to locally registered actor
              (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                               (is false
                                                                   "should not hit the wire"))
                            sliver.protocol/send-reg-message (fn [& _]
                                                               (is false
                                                                   "should not hit the wire"))]
                (ni/! node name 'hai))

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
        (ni/! node 'actor 'hai)
        (is true))))

  (testing "! doesn't barf if actor is nil"
    (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))
                  sliver.protocol/send-reg-message (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))]
      (let [node (n/node "bar" "monster" [])]
        (ni/! node nil 'hai)
        (is true))))

  (testing "! sends to local pid"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          pid    (ni/spawn node (fn []
                                 (a/receive _ (deliver result true))))]
      (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                       (is false
                                                           "should not hit the wire"))
                    sliver.protocol/send-reg-message (fn [& _]
                                                       (is false
                                                           "should not hit the wire"))]
        (ni/! node pid 'hai))
      (is (deref result 100 false))))

  (testing "! sends to remote pid"
    (let [result (promise)
          bar    (n/node "bar" "monster" [])
          foo    (n/node "spaz" "monster" [(fn [_node _from _to msg]
                                             (log/debug "FOO RECVD:" msg)
                                             (if (= msg 'hai)
                                               (deliver result true)))])]
      (ni/start foo)
      (ni/connect bar foo)

      (ni/! bar (ni/pid foo) 'hai)

      (is (deref result 1000 false))

      (ni/stop foo)))

  (testing "! sends to remote actor"
    (let [result     (promise)
          bar        (n/node "bar" "monster" [])
          spaz       (n/node "spaz" "monster"
                             [(fn [& _]
                                (deliver result true))])
          actor-name 'actor]

      (ni/start spaz)
      (ni/connect bar spaz)

      ;; need to call from within actor because internally
      ;; this uses (self node)
      (ni/spawn bar (fn []
                     (log/debug "Sending...")
                     (ni/! bar [actor-name "spaz@127.0.0.1"]
                          (into [] (range 1000)))))

      (is (deref result 10000 false))

      (ni/stop spaz))))

(deftest dead-actor-reaper-test
  (testing "spawned actors are not tracked once they're untracked"
    (let [node (n/node "bar" "monster" [])
          pid  (ni/spawn node (fn [] (+ 1 1)))]
      (ni/untrack node pid)
      (is (nil? (ni/actor-for node pid)))))

  (testing "untracking nil doesn't make everything barf"
    (let [node (n/node "bar" "monster" [])]
      (ni/untrack node nil)
      (is (nil? (ni/actor-for node nil)))))

  (testing "dead actors are reaped automatically"
    (let [node    (n/node "bar" "monster" [])
          _       (Strand/sleep 100)
          process (ni/spawn node (fn []
                                   (Strand/sleep 500)))]
      (Strand/sleep 1000)
      (is (nil? (ni/actor-for node process)))))

  (testing "dead named actors are reaped automatically"
    (let [node    (n/node "bar" "monster" [])
          _       (Strand/sleep 100)
          process (ni/spawn node (fn []
                                   (ni/register node 'foo (ni/self node))
                                   (Strand/sleep 500)))]
      (Strand/sleep 1000)
      (is (nil? (ni/whereis node 'foo))))))

(deftest monitor-processes-test
  (testing "monitoring process receives [:exit ref actor nil] when monitored finishes"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (ni/spawn node
                            (fn []
                              (ni/monitor node
                                          (ni/spawn node #(+ 1 1)))
                              (a/receive m (deliver result m))))]
      (is (and (= :exit (first @result))
               (nil? (last @result))))))

  (testing "monitoring process receives [:exit ref actor Throwable] when monitored dies"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (ni/spawn node
                            (fn []
                              (ni/monitor node
                                          (ni/spawn node
                                                    #(throw (RuntimeException. "arse"))))
                              (a/receive m (deliver result m))))]
      (is (= :exit (first @result)))
      (is (= RuntimeException (class (last @result))))))

  (testing "monitoring non-existent process doesn't kill everything"
    (let [node (n/node "bar" "monster" [])]
      (ni/monitor node (ni/pid node))
      (is true)))

  (testing "monitoring is stackable, i.e. N messages are delivered on death/done"
    (let [stacked? (promise)
          node     (n/node "bar" "monster" [])
          monitor  (ni/spawn node
                            (fn []
                              (let [spawned (ni/spawn node (fn []
                                                            (Strand/sleep 500)
                                                            (+ 1 1)))]
                                (ni/monitor node spawned)
                                (ni/monitor node spawned)
                                (a/receive
                                 _ (a/receive
                                    _ (deliver stacked? true))))))]
      (is @stacked?)))

  (testing "unwatching before death -> no messages"
    (let [demonitored? (promise)
          node         (n/node "bar" "monster" [])
          monitor      (ni/spawn node
                                (fn []
                                  (let [spawned (ni/spawn node (fn []
                                                                (Strand/sleep 1000)
                                                                (+ 1 1)))
                                        monitor (ni/monitor node spawned)]
                                    (ni/demonitor node spawned monitor)
                                    (a/receive
                                     [:exit _ _ _] (deliver demonitored? false)
                                     :after 1500   (deliver demonitored? true)))))]
      (is @demonitored?)))

  (testing "demonitoring nil doesn't barf"
    (let [ok?     (promise)
          node    (n/node "bar" "monster" [])
          monitor (ni/spawn node
                           (fn []
                             (ni/demonitor node (ni/pid node) nil)
                             (deliver ok? true)))]
      (is @ok?)))

  (testing "double demonitoring doesn't barf"
    (let [demonitored? (promise)
          node         (n/node "bar" "monster" [])
          monitor      (ni/spawn node
                                (fn []
                                  (let [spawned (ni/spawn node (fn []
                                                                (Strand/sleep 1000)
                                                                (+ 1 1)))
                                        monitor (ni/monitor node spawned)]
                                    (ni/demonitor node spawned monitor)
                                    (ni/demonitor node spawned monitor)
                                    (a/receive
                                     [:exit _ _ _] (deliver demonitored? false)
                                     :after 1500   (deliver demonitored? true)))))]
      (is @demonitored?)))

  (testing "spawn-monitor normal death"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (ni/spawn node
                            (fn []
                              (ni/spawn-monitor node
                                                #(+ 1 1))
                              (a/receive m (deliver result m))))]
      (is (and (= :exit (first @result))
               (nil? (last @result))))))

  (testing "spawn-monitor exception death"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (ni/spawn node
                            (fn []
                              (ni/spawn-monitor
                               node
                               #(throw (RuntimeException. "arse")))
                              (a/receive m (deliver result m))))]
      (is (= :exit (first @result)))
      (is (= RuntimeException (class (last @result)))))))

(deftest link-processes-test
  (testing "deaths propagate"
    (let [all-dead? (promise)
          node      (n/node "bar" "monster" [])
          chain     (fn [n f]
                      (if (zero? n)
                        (a/receive
                         _ (deliver all-dead? false)
                         :after 500 (/ 1 0))
                        (let [spawned (ni/spawn node
                                               (fn []
                                                 (f (dec n) f)))]
                          (ni/link node spawned)
                          (a/receive _ (deliver all-dead? false)))))]

      (ni/spawn node #(chain 5 chain))

      (is (deref all-dead? 1000 true))))

  (testing "link to nil doesn't barf"
    (let [barfed? (promise)
          node    (n/node "bar" "monster" [])]
      (ni/spawn node
               (fn []
                 (ni/link node nil)
                 (deliver barfed? false)))
      (is (not (deref barfed? 1000 true)))))

  (testing "link 2 independent processes"
    (let [all-dead? (promise)
          node      (n/node "bar" "monster" [])
          pid1 (ni/spawn node #(a/receive _ (deliver all-dead? false)
                                         :after 500 (/ 1 0)))
          pid2 (ni/spawn node #(a/receive _ (deliver all-dead? false)))]

      (ni/spawn node #(ni/link node pid1 pid2))

      (is (deref all-dead? 1000 true))))

  (testing "link to either nil doesn't barf"
    (let [barfed? (promise)
          node    (n/node "bar" "monster" [])]
      (ni/spawn node
               (fn []
                 (ni/link node (ni/pid node) (ni/pid node))
                 (deliver barfed? false)))
      (is (not (deref barfed? 1000 true)))))

  (testing "trapping doesn't kill the processes"
    (let [all-dead? (atom 0)
          node      (n/node "bar" "monster" [])
          chain     (fn [n f]
                      (if (zero? n)
                        (do (swap! all-dead? inc)
                            (/ 1 0))
                        (let [spawned (ni/spawn node
                                                (fn []
                                                  (f (dec n) f))
                                                {:trap true})]
                          (ni/link node spawned)
                          (a/receive [:exit _ _ _]
                                     (swap! all-dead? inc)))))]

      (ni/spawn node #(chain 4 chain) {:trap true})

      (Strand/sleep 1000)

      (is (= 5 @all-dead?))))

  (testing "spawn-link no trap"
    (let [all-dead? (promise)
          node      (n/node "bar" "monster" [])]

      (ni/spawn node
                #(do
                   (ni/spawn-link node
                                  (fn []
                                    (Strand/sleep 500)))
                   (a/receive _ (deliver all-dead? false))))

      (is (deref all-dead? 1000 true))))

  (testing "spawn-link with trap true"
    (let [exit-recvd? (promise)
          node        (n/node "bar" "monster" [])]

      (ni/spawn node
                #(do
                   (ni/spawn-link node
                                  (fn []
                                    (Strand/sleep 500)))
                   (a/receive [:exit _ _ _] (deliver exit-recvd? true)))
                {:trap true})

      (is (deref exit-recvd? 1000 false)))))
