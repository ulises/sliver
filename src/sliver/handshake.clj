(ns sliver.handshake
  (:require [bytebuffer.buff :refer [take-short take-ubyte take-uint slice-off]]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as timbre])
  (:import [java.nio ByteBuffer]))

;; as seen in https://github.com/erlang/otp/blob/maint/lib/kernel/include/dist.hrl
(defonce ^:const dflag-published 1)
(defonce ^:const dflag-extended-references 4)
(defonce ^:const dflag-extended-pid-ports 0x100)

(defn send-name-packet [^String name & flags]
  (let [bytes (concat [(byte \n) 0 5 (apply bit-or flags)]
                      (.getBytes name))
        len   (+ 7 (count name))]
    (util/flip-pack (+ 2 len)
                    (str "sbbbi" (apply str (repeat (count name) "b")))
                    (concat [len] bytes))))

(defn recv-status-packet
  [^ByteBuffer payload]
  (when (= \s (char (take-ubyte payload)))
    (keyword (apply str (map char (repeatedly (.remaining payload)
                                              #(take-ubyte payload)))))))

(defn recv-challenge-packet
  [^ByteBuffer payload]
  (when (= \n (char (take-ubyte payload)))
    (let [name-len  (- (.remaining payload) 10)
          version   (take-short payload)
          flag      (take-uint payload)
          challenge (take-uint payload)]
      {:version version :flag flag :challenge challenge
       :name (apply
              str
              (map char
                   (repeatedly name-len
                               #(take-ubyte payload))))})))

(defn send-challenge-reply-packet
  [b-challenge cookie]
  (let [tag         (byte \r)
        a-challenge (util/gen-challenge)
        digest      (util/digest b-challenge cookie)
        payload-len 21]
    {:challenge a-challenge
     :payload (util/flip-pack (+ 2 payload-len) ;; 2 bytes for message size
                              (str "sbi" (apply str (repeat 16 "b")))
                              (concat [payload-len tag a-challenge]
                                      digest))}))

(defn recv-challenge-ack-packet
  [challenge ^String cookie ^ByteBuffer payload]
  (when (= \a (char (take-ubyte payload)))
    (let [a-challenge (map (fn [n] (bit-and n 0xff))
                           (util/digest challenge cookie))
          b-challenge (repeatedly 16 #(take-ubyte payload))]
      (if (= a-challenge b-challenge)
        :ok))))

(defn handshake-packet
  [^ByteBuffer payload]
  (let [len (take-short payload)]
    (slice-off payload len)))

(defn- read-handshake-packet
  [conn handler]
  (let [raw-packet (tcp/read-handshake-packet conn)
        hs-packet  (handshake-packet raw-packet)
        decoded    (handler hs-packet)]
    (timbre/info "PACKET:" raw-packet)
    (timbre/info "HS-PACKET:" hs-packet)
    (timbre/info "DECODED: " decoded)
    decoded))

(defn send-name [connection name]
  (tcp/send-bytes connection (send-name-packet name
                                               dflag-extended-pid-ports
                                               dflag-extended-references)))

(defn recv-status [connection]
  (read-handshake-packet connection recv-status-packet))

(defn recv-challenge [connection]
  (read-handshake-packet connection recv-challenge-packet))

(defn gen-challenge [b-challenge cookie]
  (send-challenge-reply-packet (:challenge b-challenge) cookie))

(defn send-challenge [connection {:keys [payload] :as challenge}]
  (tcp/send-bytes connection payload))

(defn check-challenge-ack [connection challenge cookie]
  (read-handshake-packet connection
                         (partial recv-challenge-ack-packet
                                  challenge cookie)))
