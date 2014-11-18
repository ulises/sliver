(ns erlang-node-simulator.test-helpers
  (:require [bytebuffer.buff :refer [take-ubyte]]
            [clojure.java.io :as io]
            [clojure.test :refer [is]])
  (:import [java.nio ByteBuffer]))

(defn file->bb [filename]
  (let [file (io/file (io/resource filename))
        buffer (byte-array (.length file))]
    (.read (io/input-stream file) buffer)
    (ByteBuffer/wrap buffer)))

(defn bytes-seq [bb]
  (repeatedly (.remaining bb) #(format "%x" (take-ubyte bb))))
