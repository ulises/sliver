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

(defn maybe-split [name]
  (clojure.string/split name #"@"))

(defn plain-name [{:keys [node-name] :as maybe-name}]
  "Strips the @IP/host part of a node name"
  (if node-name
    (first (maybe-split node-name))
    (first (maybe-split maybe-name))))

(defn fqdn [{:keys [node-name host]}]
  (str node-name "@" host))

(defn register-shutdown [node name]
  (swap! (:state node) update-in
         [:shutdown-notify] conj name))

(defn writer-name [other-node]
  (symbol (str (plain-name other-node) "-writer")))

(defn reader-name [other-node]
  (symbol (str (plain-name other-node) "-reader")))
