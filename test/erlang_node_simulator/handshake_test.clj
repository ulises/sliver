(ns erlang-node-simulator.handshake-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [erlang-node-simulator.handshake :refer :all]
            [erlang-node-simulator.test-helpers :as h])
  (:import [java.nio ByteBuffer]))

(deftest test-send-name
  (testing "send-name"
    (h/bb-is-= (send-name "bar@127.0.0.1")
               (h/file->bb "send_name.bin"))))

(deftest test-recv-status-ok
  (testing "recv status ok"
    (is (= :ok (recv-status (h/file->bb "recv_status_ok.bin"))))))

(deftest test-recv-challenge
  (testing "recv challenge"
    (is (= {:challenge 0x2ad9d12a :version 0x0005
            :name "foo@127.0.0.1" :flag 0x00037ffd}
           (recv-challenge (h/file->bb "recv_challenge.bin"))))))
