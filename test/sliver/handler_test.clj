(ns sliver.handler-test
  (:require  [clojure.test :refer [deftest testing is]]
             [co.paralleluniverse.pulsar.actors :as a]
             [co.paralleluniverse.pulsar.core :as c]
             [sliver.primitive :as p]
             [sliver.node :as n]
             [sliver.test-helpers :as h])
  (:import [co.paralleluniverse.strands Strand]))

(deftest handler-ping-pong-test
  (testing "named processes sliver -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          foo   (n/node "foo" "monster")
          bar   (n/node "bar" "monster")]

      (n/start bar)
      (n/connect foo bar)

      ;; pong
      (p/spawn foo
               #(do
                  (p/register foo 'pong (p/self foo))
                  (a/receive 'ping (p/! foo ['ping "bar"] 'pong))))
      ;; ping
      (p/spawn bar
               #(do
                  (p/register bar 'ping (p/self bar))
                  (p/! bar ['pong "foo"] 'ping)
                  (a/receive 'pong (deliver done? true)
                             :after 1000 (deliver done? false))))

      (is (deref done? 2000 false))

      (n/stop foo)
      (n/stop bar)
      (h/epmd "-kill")))

  (testing "only pids sliver -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          foo   (n/node "foo" "monster")
          bar   (n/node "bar" "monster")]

      (n/start bar)
      (n/connect foo bar)

      ;; pong
      (let [ping (p/spawn foo
                          #(a/receive
                            [from 'ping] (p/! foo from [(p/self foo)
                                                        'pong])))]
        ;; ping
        (p/spawn bar
                 #(do
                    (p/! bar ping [(p/self bar) 'ping])
                    (a/receive [from 'pong] (deliver done? true)
                               :after 1000 (deliver done? false))))

        (is (deref done? 2000 false)))

      (n/stop foo)
      (n/stop bar)
      (h/epmd "-kill")))

  (testing "named processes sliver -> erlang"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [pong-received? (c/promise)
          node           (n/node "foo@127.0.0.1" "monster")
          ping           (fn []
                           (p/register node 'ping (p/self node))
                           (p/! node ['pong "bar"] 'ping)
                           (a/receive [something]
                                      _ (deliver pong-received? true)
                                      :after 5000
                                      (deliver pong-received? false)))]

      (h/escript "resources/named-processes-sliver->erlang.escript")

      (n/connect node {:node-name "bar"})

      (p/spawn node ping)

      (is @pong-received?)

      (n/stop node)
      (h/epmd "-kill")))

  (testing "named processes erlang -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [pong-received? (c/promise)
          node           (n/node "foo@127.0.0.1" "monster")
          pong           (fn []
                           (p/register node 'pong (p/self node))
                           (a/receive
                            'ping (do (p/! node ['ping "bar"] 'pong)
                                      (deliver pong-received? true))
                            :after 5000
                            (deliver pong-received? false)))]

      (n/start node)

      (p/spawn node pong)
      (Strand/sleep 1000)

      (h/escript "resources/named-processes-erlang->sliver.escript")

      (is @pong-received?)

      (n/stop node)
      (h/epmd "-kill")))

  (testing "unnamed processes sliver -> erlang"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          node  (n/node "foo" "monster")]

      (h/escript "resources/unnamed-processes-sliver->erlang.escript")

      (n/connect node {:node-name "bar"})

      (p/spawn node #(let [me (p/self node)]
                       (p/register node 'me me)
                       (p/! node ['echo "bar"] [me 'hai])
                       (a/receive [me 'hai] (deliver done? true)
                                  :after 2000 (deliver done? false))))

      (is @done?)

      (h/epmd "-kill")))

  (testing "unnamed processes erlang -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          node  (n/node "foo@127.0.0.1" "monster")]

      (n/start node)

      (p/spawn node #(let [me (p/self node)]
                       (p/register node 'me me)
                       (a/receive [from 'hai] (do (p/! node from
                                                       [from 'hai])
                                                  (deliver done? true))
                                  :after 5000 (deliver done? false))))
      (Strand/sleep 1000)

      (h/escript "resources/unnamed-processes-erlang->sliver.escript")

      (is @done?)

      (h/epmd "-kill"))))
