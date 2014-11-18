(ns erlang-node-simulator.node-test
  (:require [clojure.test :refer :all]
            [erlang-node-simulator.node :refer :all]
            [erlang-node-simulator.test-helpers :as h]))

(defn- epmd-erl-fixture [f]
  (h/epmd "-daemon" "-relaxed_command_check")
  (h/erl "foo@127.0.0.1" "monster")

  (Thread/sleep 1000)

  (f)

  (h/killall "beam.smp")
  (h/epmd "-kill"))

(use-fixtures :each epmd-erl-fixture)

(deftest test-node-connect
  (let [node (node "bar@127.0.0.1" "monster")]
    (is (= :ok (connect node {:host "127.0.0.1"
                              :port (h/epmd-port "foo")})))))
