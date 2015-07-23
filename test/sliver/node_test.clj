(ns sliver.node-test
  (:require [clojure.test :refer :all]
            [sliver.node :refer :all]
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

(deftest test-node-host-connect
  (testing "connect using host network details"
    (let [node (node "bar@127.0.0.1" "monster" [])]
      (is @(:state (connect node {:host "127.0.0.1"
                                  :port (h/epmd-port "foo")}))))))

(deftest test-node-name-connect
  (testing "connect using node name"
    (let [node (node "bar@127.0.0.1" "monster" [])]
      (is @(:state (connect node {:node-name "foo"}))))))

(deftest test-node-prefer-host-config-connect
  (testing "prefer config over name/epmd info"
    (with-redefs [sliver.epmd/port (fn [& _] -1)]
      (let [node (node "bar@127.0.0.1" "monster" [])]
        (is @(:state (connect node {:node-name "foo"
                                    :host "localhost"
                                    :port (h/epmd-port "foo")})))))))

(deftest test-node-connects-to-multiple-erlang-nodes
  (testing "connect using node name"
    (let [node (node "bar@127.0.0.1" "monster" [])]
      (connect node {:node-name "foo"})
      (connect node {:node-name "foo2"})
      (is (= '({:node-name "foo2"} {:node-name "foo"})
             (keys @(:state node)))))))

(deftest test-multiple-nodes-can-coexist
  (testing "connecting from several nodes to same erlang node"
    (let [node1  (node "bar@127.0.0.1" "monster" [])
          node2 (node "baz@127.0.0.1" "monster" [])]
      (connect node1 {:node-name "foo"})
      (connect node2 {:node-name "foo"})
      (is (= '({:node-name "foo"}) (keys @(:state node1))))
      (is (= '({:node-name "foo"}) (keys @(:state node2)))))))

(deftest test-pid-minting
  (testing "creating a new pid increments the pid count"
    (let [node (node "bar@127.0.0.1" "monster" [])]
      (is (< (:pid (pid node)) (:pid (pid node))))))

  (testing "creating too many pids rolls pid counter over"
    (let [node (node "bar@127.0.0.1" "monster" [])]
      (let [a-pid (pid node)]
        (doseq [_ (range 0xfffff)]
          (pid node))
        (is (< (:serial a-pid) (:serial (pid node))))))))
