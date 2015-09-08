(ns sliver.util
  (:require [bytebuffer.buff :refer [pack byte-buffer]])
  (:import [java.nio ByteBuffer]
           [java.security MessageDigest]))

(defn flip-pack
  [size fmt bytes-seq]
  (.flip ^ByteBuffer (apply pack (byte-buffer size) fmt bytes-seq)))

(defn gen-challenge []
  (rand-int Integer/MAX_VALUE))

(defn- md5 [^String string]
  (let [^MessageDigest md5 (MessageDigest/getInstance "MD5")]
    (do (.reset md5)
        (.update md5 (.getBytes string))
        (.digest md5))))

(defn digest
  [challenge cookie]
  (let [dig (md5 (str cookie (String/valueOf challenge)))]
    (take 16 dig)))

(defn plain-name [fqdn]
  "Strips the @IP/host part of a node name"
  (first (clojure.string/split fqdn #"@")))
