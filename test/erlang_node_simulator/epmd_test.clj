(ns erlang-node-simulator.epmd-test
  (:require [clojure.test :refer :all]
            [erlang-node-simulator.epmd :refer :all]
            [erlang-node-simulator.tcp :as tcp]
            [erlang-node-simulator.test-helpers :as h]))

(deftest test-alive2-req
  (is (= (h/file->bb "epmd_alive2_req.bin")
         (alive2-req "spaz" 62211))))

(deftest test-alive2-resp
  (is (= :ok (alive2-resp (h/file->bb "epmd_alive2_resp.bin")))))

(deftest test-full-registration
  (h/epmd "-daemon" "-relaxed_command_check")
  (Thread/sleep 1000)

  (with-open [conn (tcp/client "localhost" 4369)]
    (is (= :ok (register conn "foo" 9999)))
    (is (= 9999 (h/epmd-port "foo"))))

  (h/epmd "-kill"))

(deftest test-port2-req
  (is (= (h/file->bb "epmd_port2_req.bin")
         (port2-req "foo"))))

(deftest test-port2-resp
  (is (= 51761 (port2-resp (h/file->bb "epmd_port2_resp.bin")))))

(deftest test-full-port-request
  (testing "getting the port of a native erlang node"
    (h/epmd "-daemon" "-relaxed_command_check")
    (h/erl "foo@127.0.0.1" "monster")

    (Thread/sleep 1000)

    (with-open [conn (tcp/client "localhost" 4369)]
      (is (= (h/epmd-port "foo") (port conn "foo"))))

    (h/killall "beam.smp")
    (h/epmd "-kill"))

  (testing "getting the port of a simulated erlang node"
    (h/epmd "-daemon" "-relaxed_command_check")

    (Thread/sleep 1000)

    (with-open [register-conn (tcp/client "localhost" 4369)
                query-conn    (tcp/client "localhost" 4369)]
      (register register-conn "foo" 9999)
      (is (= 9999 (port query-conn "foo"))))

    (h/epmd "-kill")))
