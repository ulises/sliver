(ns sliver.node-test
  (:require [clojure.test :refer :all]
            [sliver.node :as n]
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
