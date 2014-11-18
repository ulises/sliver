(ns erlang-node-simulator.epmd-test
  (:require [clojure.test :refer :all]
            [erlang-node-simulator.epmd :refer :all]
            [erlang-node-simulator.test-helpers :as h]))

(deftest test-alive2-req
  (is (= (h/file->bb "epmd_alive2_req.bin")
         (alive2-req "spaz" 62211))))
