(ns sliver.handler-test
  (:require  [clojure.test :refer [deftest testing is]]
             [co.paralleluniverse.pulsar.actors :as a]
             [co.paralleluniverse.pulsar.core :as c]
             [sliver.node-interface :as ni]
             [sliver.node :as n]
             [sliver.epmd :as epmd]
             [sliver.test-helpers :as h]
             [sliver.util :as util]
             [taoensso.timbre :as timbre]))

(deftest handler-ping-pong-test
  (testing "named processes sliver -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          foo   (n/node "foo" "monster")
          bar   (n/node "bar" "monster")]

      (ni/start bar)
      (ni/connect foo bar)

      ;; pong
      (ni/spawn foo
                #(do
                   (ni/register foo 'pong (ni/self foo))
                   (a/receive 'ping (ni/! foo ['ping "bar"] 'pong))))
      ;; ping
      (ni/spawn bar
                #(do
                   (ni/register bar 'ping (ni/self bar))
                   (ni/! bar ['pong "foo"] 'ping)
                   (a/receive 'pong (deliver done? true)
                              :after 1000 (deliver done? false))))

      (is (deref done? 2000 false))

      (ni/stop foo)
      (ni/stop bar)
      (h/epmd "-kill")))

  (testing "only pids sliver -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          foo   (n/node "foo" "monster")
          bar   (n/node "bar" "monster")]

      (ni/start bar)
      (ni/connect foo bar)

      ;; pong
      (let [ping (ni/spawn foo
                           #(a/receive
                             [from 'ping] (ni/! foo from [(ni/self foo)
                                                          'pong])))]
        ;; ping
        (ni/spawn bar
                  #(do
                     (ni/! bar ping [(ni/self bar) 'ping])
                     (a/receive [from 'pong] (deliver done? true)
                                :after 1000 (deliver done? false))))

        (is (deref done? 2000 false)))

      (ni/stop foo)
      (ni/stop bar)
      (h/epmd "-kill")))

  (testing "named processes sliver -> erlang"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [pong-received? (c/promise)
          node           (n/node "foo@127.0.0.1" "monster")
          ping           (fn []
                           (ni/register node 'ping (ni/self node))
                           (ni/! node ['pong "bar"] 'ping)
                           (a/receive [something]
                                      _ (deliver pong-received? true)
                                      :after 5000
                                      (deliver pong-received? false)))]

      (h/escript "resources/named-processes-sliver->erlang.escript")

      (ni/connect node {:node-name "bar"})

      (ni/spawn node ping)

      (is @pong-received?)

      (ni/stop node)
      (h/epmd "-kill")))

  (testing "named processes erlang -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [pong-received? (c/promise)
          node           (n/node "foo@127.0.0.1" "monster")
          pong           (fn []
                           (ni/register node 'pong (ni/self node))
                           (a/receive
                            'ping (do (ni/! node ['ping "bar"] 'pong)
                                      (deliver pong-received? true))
                            :after 5000
                            (deliver pong-received? false)))]

      (ni/start node)

      (ni/spawn node pong)

      (h/escript "resources/named-processes-erlang->sliver.escript")

      (is @pong-received?)

      (ni/stop node)
      (h/epmd "-kill")))

  (testing "unnamed processes sliver -> erlang"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          node  (n/node "foo" "monster")]

      (h/escript "resources/unnamed-processes-sliver->erlang.escript")

      (ni/connect node {:node-name "bar"})

      (ni/spawn node #(let [me (ni/self node)]
                        (ni/register node 'me me)
                        (ni/! node ['echo "bar"] [me 'hai])
                        (a/receive [me 'hai] (deliver done? true)
                                   :after 2000 (deliver done? false))))

      (is @done?)

      (h/epmd "-kill")))

  (testing "unnamed processes erlang -> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [done? (c/promise)
          node  (n/node "foo@127.0.0.1" "monster")]

      (ni/start node)

      (Thread/sleep 100)

      (ni/spawn node #(let [me (ni/self node)]
                        (ni/register node 'me me)
                        (a/receive [from 'hai] (do (ni/! node from
                                                         [from 'hai])
                                                   (deliver done? true))
                                   :after 5000 (deliver done? false))))

      (h/escript "resources/unnamed-processes-erlang->sliver.escript")

      (is @done?)

      (h/epmd "-kill"))))
