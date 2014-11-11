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










