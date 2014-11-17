(ns erlang-node-simulator.node-test
  (:require [clojure.java.shell :refer [sh]]
            [clojure.test :refer :all]
            [erlang-node-simulator.node :refer :all]))

(defn- erl [name cookie]
  (future (sh "erl" "-name" name "-setcookie" cookie "-noshell")))

(defn- epmd [arg]
  (sh "epmd" arg))

(defn- epmd-port [name]
  (let [o          (:out (sh "epmd" "-names"))
        match      (re-find (re-pattern (format "name %s at port (\\d+)" name)) o)
        maybe-port (second match)]
    (assert maybe-port)
    (Integer/parseInt maybe-port)))

(defn- killall [name]
  (sh "killall" name))

(defn- epmd-erl-fixture [f]
  (epmd "-daemon -relaxed_command_check")
  (erl "foo@127.0.0.1" "monster")

  (Thread/sleep 1000)

  (f)

  (killall "erl")
  (epmd "-kill"))

(use-fixtures :each epmd-erl-fixture)

(deftest test-node-connect
  (let [node (node "bar@127.0.0.1" "monster")]
    (is (= :ok (connect node {:host "127.0.0.1"
                              :port (epmd-port "foo")})))))
