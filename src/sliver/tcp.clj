(ns sliver.tcp
  (:require [bytebuffer.buff :refer [byte-buffer take-short take-int]])
  (:import [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]))

(defn client [^String host ^Integer port]
  (let [address (InetSocketAddress. host port)]
    (SocketChannel/open address)))

(defn send-bytes [^SocketChannel client ^ByteBuffer bytes]
  (.write client bytes))

(defn read-bytes [^SocketChannel client ^Integer n]
  (let [^ByteBuffer buffer (byte-buffer n)]
    (.read client buffer)
    (.flip buffer)))

(defn concat-buffers [^ByteBuffer b1 ^ByteBuffer b2]
  (ByteBuffer/wrap (byte-array (concat (.array b1) (.array b2)))))

(defn read-handshake-packet [^SocketChannel client]
  (let [^ByteBuffer size (read-bytes client 2)
        len  (take-short size)]
    (.rewind size)
    (concat-buffers size (read-bytes client len))))

(defn read-connected-packet [^SocketChannel client]
  (let [^ByteBuffer size (read-bytes client 4)
        len  (take-int size)]
    (.rewind size)
    (concat-buffers size (read-bytes client len))))
