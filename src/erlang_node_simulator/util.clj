(ns erlang-node-simulator.util
  (:require [bytebuffer.buff :refer [pack byte-buffer]])
  (:import [java.nio ByteBuffer]))

(defn flip-pack
  [size fmt bytes-seq]
  (.flip ^ByteBuffer (apply pack (byte-buffer size) fmt bytes-seq)))
