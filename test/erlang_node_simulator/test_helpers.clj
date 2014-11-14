(ns erlang-node-simulator.test-helpers
  (:require [clojure.java.io :as io]
            [clojure.test :refer [is]])
  (:import [java.nio ByteBuffer]))

(defn file->bb [filename]
  (let [file (io/file (io/resource filename))
        buffer (byte-array (.length file))]
    (.read (io/input-stream file) buffer)
    (ByteBuffer/wrap buffer)))
