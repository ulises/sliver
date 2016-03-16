(ns sliver.util-test
  (:require [sliver.primitive :refer [pid]]
            [sliver.node :as n]
            [sliver.util :as u]
            [clojure.test :refer [deftest testing is]]))

(deftest test-with-node-macro
  (testing "creating a new pid using implicit node"
    (is (= (:node (u/with-node (n/node "bar@127.0.0.1" "monster")
                    (pid)))
           (symbol "bar@127.0.0.1")))))
