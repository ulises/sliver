(ns sliver.node-test
  (:require [clojure.test :refer :all]
            [sliver.node :refer :all]
            [sliver.test-helpers :as h]))

(defn- epmd-erl-fixture [f]
  (h/epmd "-daemon" "-relaxed_command_check")
  (h/erl "foo@127.0.0.1" "monster")

  (Thread/sleep 1000)

  (f)

  (h/killall "beam.smp")
  (h/epmd "-kill"))

(use-fixtures :each epmd-erl-fixture)

(deftest test-node-host-connect
  (testing "connect using host network details"
    (let [node (node "bar@127.0.0.1" "monster")]
      (is (= :ok (connect node {:host "127.0.0.1"
                                :port (h/epmd-port "foo")}))))))

(deftest test-node-name-connect
  (testing "connect using node name"
    (let [node (node "bar@127.0.0.1" "monster")]
      (is (= :ok (connect node {:name "foo"}))))))

(deftest test-node-prefer-host-config-connect
  (testing "prefer config over name/epmd info"
    (with-redefs [sliver.epmd/port (fn [& _] -1)]
      (let [node (node "bar@127.0.0.1" "monster")]
        (is (= :ok (connect node {:name "foo"
                                  :host "localhost"
                                  :port (h/epmd-port "foo")})))))))
