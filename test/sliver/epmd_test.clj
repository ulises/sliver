(ns sliver.epmd-test
  (:require [clojure.test :refer :all]
            [sliver.epmd :refer :all]
            [sliver.tcp :as tcp]
            [sliver.test-helpers :as h]
            [co.paralleluniverse.pulsar.actors :as a])
  (:import [co.paralleluniverse.strands Strand]))

(deftest test-alive2-req
  (is (= (h/file->bb "epmd_alive2_req.bin")
         (alive2-req "spaz" 62211))))

(deftest test-alive2-resp
  (is (= :ok (alive2-resp (h/file->bb "epmd_alive2_resp.bin")))))

(deftest test-full-registration
  (h/epmd "-daemon" "-relaxed_command_check")
  (Strand/sleep 1000)

  (let [status-ok? (promise)
        foo-port   (promise)]
    (a/spawn
     #(with-open [conn (client)]
        (deliver status-ok? (:status (register conn "foo" 9999)))
        (deliver foo-port (h/epmd-port "foo"))))

    (is (= :ok (deref status-ok? 1000 :not-ok)))
    (is (= 9999 (deref foo-port 1000 -1))))

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

    (Strand/sleep 1000)

    (let [port-matches? (promise)]
      (a/spawn
       #(with-open [conn (client)]
          (deliver port-matches? (= (h/epmd-port "foo") (port conn "foo")))))

      (is (deref port-matches? 1000 false)))

    (h/killall "beam.smp")
    (h/epmd "-kill"))

  (testing "getting the port of a simulated erlang node"
    (h/epmd "-daemon" "-relaxed_command_check")

    (Strand/sleep 1000)

    (let [foo-port (promise)]
      (a/spawn
       #(with-open [register-conn (client)
                    query-conn    (client)]
          (register register-conn "foo" 9999)
          (deliver foo-port (= 9999 (port query-conn "foo")))))
      (is (deref foo-port 1000 false)))

    (h/epmd "-kill")))

(deftest test-closing-connection-unregisters
  (h/epmd "-daemon" "-relaxed_command_check")
  (let [foobar-port (promise)]
    (a/spawn
     #(let [epmd-client  (client)
            _            (register epmd-client "foobar" 9999)]

        ;; unregister
        (.close epmd-client)
        (deliver foobar-port (port (client) "foobar"))))
    (is (zero? (deref foobar-port 1000 -1))))
  (h/epmd "-kill"))
