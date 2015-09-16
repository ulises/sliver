(ns sliver.handshake-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [sliver.handshake :refer :all]
            [sliver.node :as n]
            [sliver.test-helpers :as h]
            [taoensso.timbre :as timbre])
  (:import [java.nio ByteBuffer]))

(deftest test-read-packet
  (testing "reads a packet"
    (is (= (handshake-packet (ByteBuffer/wrap (byte-array [0 3 1 2 3])))
           (ByteBuffer/wrap (byte-array [1 2 3]))))))


(deftest test-send-name
  (testing "send-name"
    (is (= (send-name-packet "bar@127.0.0.1" 0xfd 0x7f00 0x30000)
           (h/file->bb "send_name.bin")))))

(deftest test-receive-name
  (testing "receive-name"
    (is (= "bar@127.0.0.1" (receive-name-packet
                            (handshake-packet
                             (h/file->bb "send_name.bin")))))))

(deftest test-recv-status-ok
  (testing "recv status ok"
    (is (= :ok (recv-status-packet
                (handshake-packet
                 (h/file->bb "recv_status_ok.bin")))))))

(deftest test-send-status
  (testing "send status ok"
    (is (= (send-status-packet :ok)
           (h/file->bb "recv_status_ok.bin"))))

  (testing "can recv status from send-status-packet"
    (is (= :random (recv-status-packet (handshake-packet
                                        (send-status-packet :random)))))))

(deftest test-recv-challenge
  (testing "recv challenge"
    (is (= {:challenge 0x2ad9d12a :version version
            :name "foo@127.0.0.1" :flag 229373}
           (recv-challenge-packet
            (handshake-packet (h/file->bb "recv_challenge.bin")))))))

(deftest test-send-challenge
  (testing "send challenge"
    (let [challenge-packet (send-challenge-packet "foo@127.0.0.1"
                                                  0x2ad9d12a)]
      (is (= (recv-challenge-packet
              (handshake-packet challenge-packet))
             {:version 0x0005 :name "foo@127.0.0.1" :flag flag
              :challenge 0x2ad9d12a})))))

(deftest test-send-challenge-reply
  (testing "send challenge reply"
    (with-redefs [clojure.core/rand-int (fn [n] 0xa5c072f1)]
      (let [a-challenge (send-challenge-reply-packet 0x2ad9d12a
                                                     "ZQHEBZYTXKIPJNBSCYEN")]
        (is (= (:payload a-challenge)
               (h/file->bb "send_challenge.bin")))
        (is (= (:challenge a-challenge) 0xa5c072f1))))))

(deftest test-recv-challenge-reply
  (testing "recv challenge reply"
    (is (= {:challenge 0xa5c072f1
            :digest [-100 92 -115 74 68 -91 25 6 -8 -119 -108 64 -72 39 -37
                     120]}
           (recv-challenge-reply-packet
            (handshake-packet (h/file->bb "send_challenge.bin")))))))

(deftest test-send-challenge-ack
  (testing "sending successful challenge ack"
    (is (= (h/file->bb "recv_challenge_ack.bin")
           (send-challenge-ack-packet 0xa5c072f1 "ZQHEBZYTXKIPJNBSCYEN")))))

(deftest test-recv-challenge-ack
  (testing "recv challenge ack - accepted"
    (is (= :ok
           (recv-challenge-ack-packet
            0xa5c072f1 "ZQHEBZYTXKIPJNBSCYEN"
            (handshake-packet (h/file->bb "recv_challenge_ack.bin"))))))

  (testing "recv challenge ack - wrong cookie"
    (is (not (recv-challenge-ack-packet
              0xa5c072f1 "monster"
              (handshake-packet
               (h/file->bb "recv_challenge_ack.bin"))))))

  (testing "recv challenge ack - wrong challenge"
    (is (not (recv-challenge-ack-packet
              0 "ZQHEBZYTXKIPJNBSCYEN"
              (handshake-packet
               (h/file->bb "recv_challenge_ack.bin")))))))

(deftest native-handshake-test
  (testing "successful handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     {:node-name "foo@127.0.0.1" :cookie cookie}
          bar-node {:node-name "bar@127.0.0.1" :cookie cookie}]

      (h/erl "bar@127.0.0.1" cookie)

      (Thread/sleep 5000)

      ;; connect nodes
      (is (= :ok (:status (do-handshake node bar-node))))

      (h/killall "beam.smp")
      (h/epmd "-kill")))

  (testing "alive handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     {:node-name "foo@127.0.0.1" :cookie cookie}
          bar-node {:node-name "bar@127.0.0.1" :cookie cookie}]

      (h/erl "bar@127.0.0.1" cookie)

      (Thread/sleep 1000)

      ;; connect nodes
      (is (= :ok (:status (do-handshake node bar-node))))

      ;; the old connection is live, this should not return a new connected
      ;; socket.
      (is (= {:status :alive :connection nil}
             (do-handshake node bar-node)))

      (h/killall "beam.smp")
      (h/epmd "-kill")))

  (testing "wrong cookies native -connect-> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node (n/node "bar@127.0.0.1" "monster" [])]

      (n/start node)

      (h/escript "resources/wrong.cookie.native->sliver.escript")

      (is (= [:epmd-socket :server-socket]
             (keys @(:state node))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "wrong cookies sliver -connect-> native"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [foo  "foo@127.0.0.1"
          node (n/node "bar@127.0.0.1" "monster" [])]

      (h/erl foo "random")
      (Thread/sleep 1000)

      (n/connect node {:node-name "foo"})

      ;; the node hasn't been started, so no connections here
      (is (not (keys @(:state node))))

      (h/killall "beam.smp")
      (h/epmd "-kill"))))

(deftest sliver-handshake-test
  (testing "successful handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node {:node-name "bar@127.0.0.1" :cookie cookie}]

      (n/start node)

      ;; connect nodes
      (is (= :ok (:status (do-handshake bar-node node))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "alive handshake"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node {:node-name "bar@127.0.0.1" :cookie cookie}]

      (n/start node)

      ;; connect nodes
      (is (= :ok (:status (do-handshake bar-node node))))

      ;; the old connection is live, this should not return a new connected
      ;; socket.
      (is (= {:status :alive :connection nil}
             (do-handshake bar-node node)))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "wrong cookies sliver -connect-> sliver"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [cookie   "monster"
          node     (n/node "foo@127.0.0.1" cookie [])
          bar-node {:node-name "bar@127.0.0.1" :cookie "random"}]

      (n/start node)

      ;; connect nodes
      (is (= :error (:status (do-handshake bar-node node))))

      (n/stop node)
      (h/epmd "-kill")))

  (testing "wrong cookies sliver -connect-> sliver II"
    (h/epmd "-daemon" "-relaxed_command_check")
    (let [node     (n/node "foo@127.0.0.1" "monster" [])
          bar-node (n/node  "bar@127.0.0.1" "random" [])]

      (n/start bar-node)

      (n/connect node bar-node)

      (n/stop node)
      (h/epmd "-kill"))))
