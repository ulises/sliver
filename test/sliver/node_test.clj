(ns sliver.node-test
  (:require [clojure.test :refer :all]
            [sliver.node :as n]
            [sliver.primitive :as p]
            [co.paralleluniverse.pulsar.actors :as a]
            [co.paralleluniverse.pulsar.core :as c]
            [sliver.test-helpers :as h]
            [taoensso.timbre :as log])
  (:import [co.paralleluniverse.strands Strand]))

(deftest test-pid-minting
  (testing "creating a new pid increments the pid count"
    (let [node (n/node "bar@127.0.0.1" "monster" [])]
      (is (< (:pid (p/pid node)) (:pid (p/pid node))))
      (n/stop node)))

  (testing "creating too many pids rolls pid counter over"
    (let [node (n/node "bar@127.0.0.1" "monster" [])]
      (let [a-pid (p/pid node)]
        (doseq [_ (range 0xfffff)]
          (p/pid node))
        (is (< (:serial a-pid) (:serial (p/pid node))))
        (n/stop node)))))

(deftest test-making-references
  (testing "creating a reference"
    (let [node      (n/node "bar@127.0.0.1" "monster" [])
          pid       (p/pid node)
          reference (p/make-ref node pid)]
      (is "bar@127.0.0.1" (:node reference))
      (is pid (:pid reference)))))

(deftest spawn-test
  (testing "Spawning a process returns a pid"
    (let [node (n/node "bar" "monster" [])]
      (is (= borges.type.Pid (type (p/spawn node #(+ 1 1)))))))

  (testing "Spawned actor is tracked"
    (let [node  (n/node "bar" "monster" [])
          pid   (p/spawn node #(Strand/sleep 5000))
          actor (p/actor-for node pid)]
      (is (p/actor-for node pid))
      (is (= pid (p/pid-for node actor)))))

  (testing "Spawned actor actually does work"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          _ (p/spawn node #(deliver result 'did-it))]
      (is (= 'did-it (deref result 100 'didnt-do-it))))))

(deftest send-messages-to-local-processes-test

  (testing "nil pid does not break everything"
    (let [node (n/node "bar" "monster" [])]
      (p/send-message node nil 'hai)
      (is true)))

  (testing "local message doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (promise)
            node   (n/node "bar" "monster" [])
            pid1   (p/spawn node (fn []
                                   (a/receive
                                    m (deliver result m))))]
        (p/send-message node pid1 'success)

        (is (= 'success (deref result 100 'failed))))))

  (testing "local message to non-existing process doesn't kill everything"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [node   (n/node "bar" "monster" [])]
        ;; send to non-existing process
        (p/send-message node (p/pid node) 'success)
        ;; only to get an assertion here
        (is true))))

  (testing "local ping pong doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (promise)
            node   (n/node "bar" "monster" [])
            pid1   (p/spawn node (fn []
                                   (a/receive
                                    [from m] (p/send-message node from m))))]
        (p/spawn node (fn []
                        (p/send-message node pid1 [(p/self node)
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
          pid1   (p/spawn node #(deliver result (p/self node)))]
      (is (= pid1 (deref result 100 'fail))))))

(deftest register-named-processes-test
  (testing "can register a process with a name (symbol)"
    (let [node       (n/node "bar" "monster" [])
          name       'clint-eastwood
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (is (= name actor-name))))

  (testing "can register a process with a name (keyword)"
    (let [node       (n/node "bar" "monster" [])
          name       :clint-eastwood
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (is (= name actor-name))))

  (testing "can register a process with a name (string)"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (is (= name actor-name))))

  (testing "registering a process with an already registered name fails"
    (let [node        (n/node "bar" "monster" [])
          name        'clint-eastwood
          pid         (p/spawn node #(a/receive))
          pid2        (p/spawn node #(a/receive))
          actor-name1 (p/register node name pid)
          actor-name2 (p/register node name pid2)]
      (is (= name actor-name1))
      (is (nil? actor-name2))))

  (testing "registering a process with an already registered name fails (many)"
    (let [node        (n/node "bar" "monster" [])
          name        'clint-eastwood
          successful  (atom 0)
          failed      (atom 0)
          actor-fn    (fn []
                        (if-let [name (p/register node name (p/self node))]
                          (swap! successful inc)
                          (swap! failed inc))
                        (a/receive))]
      (doall
       (for [_ (range 100)]
         (p/spawn node actor-fn)))

      (Strand/sleep 1000)

      (is (= 1 @successful))
      (is (= 99 @failed))))

  (testing "can't register with nil name"
    (let [node       (n/node "bar" "monster" [])
          name       nil
          pid        (p/spawn node #(+ 1 1))
          actor-name (p/register node name pid)]
      (is (nil? actor-name))
      (is (nil? (p/whereis node name)))))

  ;; test for registering with anything else -> fail
  ;; in erlang valid names are just atoms, however global
  ;; (https://github.com/erlang/otp/blob/maint/lib/kernel/src/global.erl)
  ;; allows anything to be a name. Why not do it from the get-go here?

  (testing "can find an actor based on its name (symbol)"
    (let [node       (n/node "bar" "monster" [])
          name       'clint-eastwood
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (is (= pid (p/whereis node actor-name)))))

  (testing "can find an actor based on its name (keyword)"
    (let [node       (n/node "bar" "monster" [])
          name       :clint-eastwood
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (is (= pid (p/whereis node actor-name)))))

  (testing "can find an actor based on its name (string)"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (is (= pid (p/whereis node actor-name)))))

  (testing "can unregister a registered actor"
    (let [node       (n/node "bar" "monster" [])
          name       'actor
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (p/unregister node actor-name)
      (is (nil? (p/whereis node actor-name)))))

  (testing "can find a registered actor's name from its pid"
    (let [node       (n/node "bar" "monster" [])
          name       "clint eastwood"
          pid        (p/spawn node #(a/receive))
          actor-name (p/register node name pid)]
      (is (= actor-name (p/name-for node pid))))))

(deftest send-messages-to-local-registered-processes-test
  (testing "local message doesn't hit the wire"
    (let [result (promise)
          node   (n/node "bar" "monster" [])]

      (p/spawn node (fn []
                       (p/register node 'actor (p/self node))
                       (a/receive m (deliver result m))))
      (Strand/sleep 1000)

      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (is false "messages should not hit the wire"))]
        (p/send-registered-message node 'ignored-pid 'actor "bar@127.0.0.1"
                                    'success))

      (is (= 'success (deref result 1000 'failed)))))

  (testing "local message to non-existing process doesn't kill everything"
    (with-redefs [sliver.protocol/send-reg-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [node   (n/node "bar" "monster" [])]
        ;; send to non-existing process
        (p/send-registered-message node (p/pid node) 'foobar "bar@127.0.0.1"
                                   'success)

        ;; only to get an assertion here
        (is true))))

  (testing "non-local message should hit the wire"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [messages-sent (atom 0)]
      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (reset! messages-sent 1))]
        (let [bar  (n/node "bar" "monster" [])
              spaz (n/node "spaz" "monster" [])]

          (n/start spaz)
          (n/connect bar spaz)

          (p/send-registered-message bar (p/pid bar) 'actor "spaz@127.0.0.1"
                                      'success)

          (is (= 1 @messages-sent))

          (n/stop spaz))))
    (h/epmd "-kill"))

  (testing "non-local message should hit the wire even if there's a local process"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [messages-sent (atom 0)]
      (with-redefs [sliver.protocol/send-reg-message
                    (fn [& _]
                      (reset! messages-sent 1))]
        (let [bar  (n/node "bar" "monster" [])
              spaz (n/node "spaz" "monster" [])
              _    (p/register bar 'actor
                                (p/spawn bar #(a/receive m :ok
                                                          :after 5000 :ok)))]

          (n/start spaz)
          (n/connect bar spaz)

          (p/send-registered-message bar (p/pid bar) 'actor "spaz@127.0.0.1"
                                     'success)

          (Strand/sleep 100)

          (is (= 1 @messages-sent))

          (n/stop spaz))))

    (h/epmd "-kill"))

  (testing "local registered ping pong doesn't hit the wire"
    (with-redefs [sliver.protocol/send-message
                  (fn [& _]
                    (is false "messages should not hit the wire"))]
      (let [result (atom 0)
            node   (n/node "bar" "monster" [])
            pid1   (p/spawn node (fn []
                                    (p/register node 'pong (p/self node))
                                    (a/receive
                                     'ping (p/send-registered-message node
                                                                       'ignored
                                                                       'ping
                                                                       "bar@127.0.0.1"
                                                                       'pong))))]
        (p/spawn node (fn []
                         (p/register node 'ping (p/self node))
                         (p/send-registered-message node 'ignored 'pong
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
                  _pid        (p/spawn node (fn []
                                               (p/register node name
                                                            (p/self node))
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
                (p/! node name 'hai))

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
        (p/! node 'actor 'hai)
        (is true))))

  (testing "! doesn't barf if actor is nil"
    (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))
                  sliver.protocol/send-reg-message (fn [& _]
                                                     (is false
                                                         "should not hit the wire"))]
      (let [node (n/node "bar" "monster" [])]
        (p/! node nil 'hai)
        (is true))))

  (testing "! sends to local pid"
    (let [result (promise)
          node   (n/node "bar" "monster" [])
          pid    (p/spawn node (fn []
                                  (a/receive _ (deliver result true))))]
      (with-redefs [sliver.protocol/send-message     (fn [& _]
                                                       (is false
                                                           "should not hit the wire"))
                    sliver.protocol/send-reg-message (fn [& _]
                                                       (is false
                                                           "should not hit the wire"))]
        (p/! node pid 'hai))
      (is (deref result 100 false))))

  (testing "! sends to remote pid"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [result (promise)
          bar    (n/node "bar" "monster" [])
          foo    (n/node "foo" "monster" [(fn [_node _from _to msg]
                                            (log/debug "FOO RECVD:" msg)
                                            (if (= msg 'hai)
                                              (deliver result true)))])]
      (n/start foo)
      (n/connect bar foo)

      (p/! bar (p/pid foo) 'hai)

      (is (deref result 1000 false))

      (n/stop foo))
    (h/epmd "-kill"))

  (testing "! sends to remote actor"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [result     (promise)
          bar        (n/node "bar" "monster" [])
          spaz       (n/node "spaz" "monster"
                             [(fn [& _]
                                (deliver result true))])
          actor-name 'actor]

      (n/start spaz)
      (n/connect bar spaz)

      ;; need to call from within actor because internally
      ;; this uses (self node)
      (p/spawn bar (fn []
                      (log/debug "Sending...")
                      (p/! bar [actor-name "spaz@127.0.0.1"]
                            (into [] (range 1000)))))

      (is (deref result 10000 false))

      (n/stop spaz))

    (h/epmd "-kill"))

  (testing "! to registered process returns message"
    (let [node (n/node "foo@127.0.0.1" "monster")
          p    (p/spawn node #(do (p/register node 'p (p/self node))
                                   (a/receive)))]
      (Strand/sleep 100)

      (is (= 'ohai (p/! node 'p 'ohai)))))

  (testing "! to pid returns message"
    (let [node (n/node "foo@127.0.0.1" "monster")
          pid  (p/spawn node #(a/receive))]
      (is (= 'ohai (p/! node pid 'ohai)))))

  (testing "! to remote process returns message"
    (h/epmd "-daemon" "-relaxed_command_check")

    (let [sent (c/promise)
          spaz (n/node "spaz@127.0.0.1" "monster")
          bar  (n/node "bar@127.0.0.1" "monster")
          p    (p/spawn bar #(do (p/register bar 'p (p/self bar))
                                  (a/receive)))]
      (n/start bar)
      (n/connect spaz bar)

      (p/spawn spaz #(deliver sent (p/! spaz ['p bar] 'ohai)))

      (is (= 'ohai (deref sent 1000 'not-hai))))

    (h/epmd "-kill")))

(deftest dead-actor-reaper-test
  (testing "spawned actors are not tracked once they're untracked"
    (let [node (n/node "bar" "monster" [])
          pid  (p/spawn node (fn [] (+ 1 1)))]
      (p/untrack node pid)
      (is (nil? (p/actor-for node pid)))))

  (testing "untracking nil doesn't make everything barf"
    (let [node (n/node "bar" "monster" [])]
      (p/untrack node nil)
      (is (nil? (p/actor-for node nil)))))

  (testing "dead actors are reaped automatically"
    (let [node    (n/node "bar" "monster" [])
          process (p/spawn node (fn []
                                   (Strand/sleep 500)))]
      (Strand/sleep 1000)
      (is (nil? (p/actor-for node process)))))

  (testing "dead named actors are reaped automatically"
    (let [node    (n/node "bar" "monster" [])
          process (p/spawn node (fn []
                                   (p/register node 'foo (p/self node))
                                   (Strand/sleep 500)))]
      (Strand/sleep 1000)
      (is (nil? (p/whereis node 'foo))))))

(deftest monitor-processes-test
  (testing "monitoring process receives [:exit ref actor nil] when monitored finishes"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (p/spawn node
                            (fn []
                              (p/monitor node
                                          (p/spawn node #(+ 1 1)))
                              (a/receive m (deliver result m))))]
      (is (and (= :exit (first @result))
               (nil? (last @result))))))

  (testing "monitoring process receives [:exit ref actor Throwable] when monitored dies"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (p/spawn node
                            (fn []
                              (p/monitor node
                                          (p/spawn node
                                                    #(throw (RuntimeException. "arse"))))
                              (a/receive m (deliver result m))))]
      (is (= :exit (first @result)))
      (is (= RuntimeException (class (last @result))))))

  (testing "monitoring non-existent process doesn't kill everything"
    (let [node (n/node "bar" "monster" [])]
      (p/monitor node (p/pid node))
      (is true)))

  (testing "monitoring is stackable, i.e. N messages are delivered on death/done"
    (let [stacked? (promise)
          node     (n/node "bar" "monster" [])
          monitor  (p/spawn node
                            (fn []
                              (let [spawned (p/spawn node (fn []
                                                            (Strand/sleep 500)
                                                            (+ 1 1)))]
                                (p/monitor node spawned)
                                (p/monitor node spawned)
                                (a/receive
                                 _ (a/receive
                                    _ (deliver stacked? true))))))]
      (is @stacked?)))

  (testing "unwatching before death -> no messages"
    (let [demonitored? (promise)
          node         (n/node "bar" "monster" [])
          monitor      (p/spawn node
                                (fn []
                                  (let [spawned (p/spawn node (fn []
                                                                (Strand/sleep 1000)
                                                                (+ 1 1)))
                                        monitor (p/monitor node spawned)]
                                    (p/demonitor node spawned monitor)
                                    (a/receive
                                     [:exit _ _ _] (deliver demonitored? false)
                                     :after 1500   (deliver demonitored? true)))))]
      (is @demonitored?)))

  (testing "demonitoring nil doesn't barf"
    (let [ok?     (promise)
          node    (n/node "bar" "monster" [])
          monitor (p/spawn node
                           (fn []
                             (p/demonitor node (p/pid node) nil)
                             (deliver ok? true)))]
      (is @ok?)))

  (testing "double demonitoring doesn't barf"
    (let [demonitored? (promise)
          node         (n/node "bar" "monster" [])
          monitor      (p/spawn node
                                (fn []
                                  (let [spawned (p/spawn node (fn []
                                                                (Strand/sleep 1000)
                                                                (+ 1 1)))
                                        monitor (p/monitor node spawned)]
                                    (p/demonitor node spawned monitor)
                                    (p/demonitor node spawned monitor)
                                    (a/receive
                                     [:exit _ _ _] (deliver demonitored? false)
                                     :after 1500   (deliver demonitored? true)))))]
      (is @demonitored?)))

  (testing "spawn-monitor normal death"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (p/spawn node
                            (fn []
                              (p/spawn-monitor node
                                                #(+ 1 1))
                              (a/receive m (deliver result m))))]
      (is (and (= :exit (first @result))
               (nil? (last @result))))))

  (testing "spawn-monitor exception death"
    (let [result  (promise)
          node    (n/node "bar" "monster" [])
          monitor (p/spawn node
                            (fn []
                              (p/spawn-monitor
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
                        (let [spawned (p/spawn node
                                               (fn []
                                                 (f (dec n) f)))]
                          (p/link node spawned)
                          (a/receive _ (deliver all-dead? false)))))]

      (p/spawn node #(chain 5 chain))

      (is (deref all-dead? 1000 true))))

  (testing "link to nil doesn't barf"
    (let [barfed? (promise)
          node    (n/node "bar" "monster" [])]
      (p/spawn node
               (fn []
                 (p/link node nil)
                 (deliver barfed? false)))
      (is (not (deref barfed? 1000 true)))))

  (testing "link 2 independent processes"
    (let [all-dead? (promise)
          node      (n/node "bar" "monster" [])
          pid1 (p/spawn node #(a/receive _ (deliver all-dead? false)
                                         :after 500 (/ 1 0)))
          pid2 (p/spawn node #(a/receive _ (deliver all-dead? false)))]

      (p/spawn node #(p/link node pid1 pid2))

      (is (deref all-dead? 1000 true))))

  (testing "link to either nil doesn't barf"
    (let [barfed? (promise)
          node    (n/node "bar" "monster" [])]
      (p/spawn node
               (fn []
                 (p/link node (p/pid node) (p/pid node))
                 (deliver barfed? false)))
      (is (not (deref barfed? 1000 true)))))

  (testing "trapping doesn't kill the processes"
    (let [all-dead? (atom 0)
          node      (n/node "bar" "monster" [])
          chain     (fn [n f]
                      (if (zero? n)
                        (do (swap! all-dead? inc)
                            (/ 1 0))
                        (let [spawned (p/spawn node
                                                (fn []
                                                  (f (dec n) f))
                                                {:trap true})]
                          (p/link node spawned)
                          (a/receive [:exit _ _ _]
                                     (swap! all-dead? inc)))))]

      (p/spawn node #(chain 4 chain) {:trap true})

      (Strand/sleep 1000)

      (is (= 5 @all-dead?))))

  (testing "spawn-link no trap"
    (let [all-dead? (promise)
          node      (n/node "bar" "monster" [])]

      (p/spawn node
                #(do
                   (p/spawn-link node
                                  (fn []
                                    (Strand/sleep 500)))
                   (a/receive _ (deliver all-dead? false))))

      (is (deref all-dead? 1000 true))))

  (testing "spawn-link with trap true"
    (let [exit-recvd? (promise)
          node        (n/node "bar" "monster" [])]

      (p/spawn node
                #(do
                   (p/spawn-link node
                                  (fn []
                                    (Strand/sleep 500)))
                   (a/receive [:exit _ _ _] (deliver exit-recvd? true)))
                {:trap true})

      (is (deref exit-recvd? 1000 false)))))
