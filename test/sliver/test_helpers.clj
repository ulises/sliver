(ns sliver.test-helpers
  (:require [bytebuffer.buff :refer [take-ubyte]]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer [is]])
  (:import [java.nio ByteBuffer]))

(defn file->bb [filename]
  (let [file (io/file (io/resource filename))
        buffer (byte-array (.length file))]
    (.read (io/input-stream file) buffer)
    (ByteBuffer/wrap buffer)))

(defn bytes-seq [bb]
  (repeatedly (.remaining bb) #(format "%x" (take-ubyte bb))))

(defn erl [name cookie]
  (future (sh "erl" "-name" name "-setcookie" cookie "-noshell")))

(defn epmd [& args]
  (apply sh (conj args "epmd")))

(defn epmd-port [name]
  (let [o          (:out (sh "epmd" "-names"))
        match      (re-find (re-pattern (format "name %s at port (\\d+)" name)) o)
        maybe-port (second match)]
    (assert maybe-port)
    (Integer/parseInt maybe-port)))

(defn killall [name]
  (sh "killall" name))
