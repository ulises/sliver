(ns sliver.epmd-test
  (:require [clojure.test :refer :all]
            [sliver.epmd :refer :all]
            [sliver.tcp :as tcp]
            [sliver.test-helpers :as h]))

(deftest test-alive2-req
  (is (= (h/file->bb "epmd_alive2_req.bin")
         (alive2-req "spaz" 62211))))

(deftest test-alive2-resp
  (is (= :ok (alive2-resp (h/file->bb "epmd_alive2_resp.bin")))))

(deftest test-full-registration
  (h/epmd "-daemon" "-relaxed_command_check")
  (Thread/sleep 1000)

  (with-open [conn (client)]
    (is (= :ok (:status (register conn "foo" 9999))))
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

    (with-open [conn (client)]
      (is (= (h/epmd-port "foo") (port conn "foo"))))

    (h/killall "beam.smp")
    (h/epmd "-kill"))

  (testing "getting the port of a simulated erlang node"
    (h/epmd "-daemon" "-relaxed_command_check")

    (Thread/sleep 1000)

    (with-open [register-conn (client)
                query-conn    (client)]
      (register register-conn "foo" 9999)
      (is (= 9999 (port query-conn "foo"))))

    (h/epmd "-kill")))

(deftest test-closing-connection-unregisters
  (h/epmd "-daemon" "-relaxed_command_check")
  (let [epmd-client  (client)
        _            (register epmd-client "foobar" 9999)]
    (is (= 9999 (port (client) "foobar")))

    ;; unregister
    (.close epmd-client)

    (is (zero? (port (client) "foobar")))
    (h/epmd "-kill")))
