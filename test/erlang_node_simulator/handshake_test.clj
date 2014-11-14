(ns erlang-node-simulator.handshake-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [erlang-node-simulator.handshake :refer :all]
            [erlang-node-simulator.test-helpers :as h])
  (:import [java.nio ByteBuffer]))

(deftest test-read-packet
  (testing "reads a packet"
    (is (= (packet (ByteBuffer/wrap (byte-array [0 3 1 2 3])))
           (ByteBuffer/wrap (byte-array [1 2 3]))))))


(deftest test-send-name
  (testing "send-name"
    (is (= (send-name "bar@127.0.0.1")
           (h/file->bb "send_name.bin")))))

(deftest test-recv-status-ok
  (testing "recv status ok"
    (is (= :ok (recv-status
                (packet
                 (h/file->bb "recv_status_ok.bin")))))))

(deftest test-recv-challenge
  (testing "recv challenge"
    (is (= {:challenge 0x2ad9d12a :version 0x0005
            :name "foo@127.0.0.1" :flag 0x00037ffd}
           (recv-challenge (packet (h/file->bb "recv_challenge.bin")))))))

(deftest test-send-challenge-reply
  (testing "send challenge reply"
    (with-redefs [clojure.core/rand-int (fn [n] 0xa5c072f1)]
      (let [a-challenge (send-challenge-reply 0x2ad9d12a "ZQHEBZYTXKIPJNBSCYEN")]
        (is (= (:payload a-challenge)
               (h/file->bb "send_challenge.bin")))
        (is (= (:challenge a-challenge) 0xa5c072f1))))))

(deftest test-recv-challenge-ack
  (testing "recv challenge ack - accepted"
    (is (= :ok
           (recv-challenge-ack 0xa5c072f1 "ZQHEBZYTXKIPJNBSCYEN"
                               (packet
                                (h/file->bb "recv_challenge_ack.bin"))))))

  (testing "recv challenge ack - wrong cookie"
    (is (not (recv-challenge-ack 0xa5c072f1 "monster"
                                 (packet
                                  (h/file->bb "recv_challenge_ack.bin"))))))

  (testing "recv challenge ack - wrong challenge"
    (is (not (recv-challenge-ack 0 "ZQHEBZYTXKIPJNBSCYEN"
                                 (packet
                                  (h/file->bb "recv_challenge_ack.bin")))))))
