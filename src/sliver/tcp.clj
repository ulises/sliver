(ns sliver.tcp
  (:require [bytebuffer.buff :refer [byte-buffer take-short take-int]])
  (:import [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [co.paralleluniverse.fibers.io FiberSocketChannel
            FiberServerSocketChannel]))

(defn client [^String host ^Integer port]
  (let [address (InetSocketAddress. host port)]
    (FiberSocketChannel/open address)))

(defn server [^String address ^Integer port]
  (let [socket-channel (FiberServerSocketChannel/open)
        inet-address   (InetSocketAddress. address port)]
    (.bind socket-channel inet-address)
    socket-channel))

(defn send-bytes [^FiberSocketChannel client ^ByteBuffer bytes]
  (.write client bytes))

(defn read-bytes [^FiberSocketChannel client ^Integer n]
  (let [^ByteBuffer buffer (byte-buffer n)]
    (.read client buffer)
    (.flip buffer)))

(defn concat-buffers [^ByteBuffer b1 ^ByteBuffer b2]
  (ByteBuffer/wrap (byte-array (concat (.array b1) (.array b2)))))

(defn read-handshake-packet [^FiberSocketChannel client]
  (let [^ByteBuffer size (read-bytes client 2)
        len  (take-short size)]
    (.rewind size)
    (concat-buffers size (read-bytes client len))))

(defn read-connected-packet [^FiberSocketChannel client]
  (let [^ByteBuffer size (read-bytes client 4)
        len  (take-int size)]
    (.rewind size)
    (concat-buffers size (read-bytes client len))))
