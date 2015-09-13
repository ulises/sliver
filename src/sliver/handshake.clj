(ns sliver.handshake
  (:require [bytebuffer.buff :refer [take-short take-ubyte take-uint slice-off
                                     take-byte]]
            [sliver.epmd :as epmd ]
            [sliver.tcp :as tcp]
            [sliver.util :as util]
            [taoensso.timbre :as timbre])
  (:import [java.nio ByteBuffer]
           [java.nio.channels SocketChannel]))

;; as seen in https://github.com/erlang/otp/blob/maint/lib/kernel/include/dist.hrl
(defonce ^:const dflag-published 1)
(defonce ^:const dflag-extended-references 4)
(defonce ^:const dflag-extended-pid-ports 0x100)
(defonce ^:const dflag-new-floats 0x800)

(defonce ^:const version 0x0005)
(defonce ^:const flag (bit-or dflag-extended-references
                              dflag-extended-pid-ports))

(defn send-name-packet [^String name & flags]
  (let [bytes (concat [(byte \n) 0 5 (apply bit-or flags)]
                      (.getBytes name))
        len   (+ 7 (count name))]
    (util/flip-pack (+ 2 len)
                    (str "sbbbi" (apply str (repeat (count name) "b")))
                    (concat [len] bytes))))

(defn receive-name-packet [^ByteBuffer payload]
  (when (= \n (char (take-ubyte payload)))
    (let [_version (take-short payload)
          _flags   (take-uint payload)
          name-len (.remaining payload)]
      (apply str (map char (repeatedly name-len #(take-ubyte payload)))))))

(defn recv-status-packet
  [^ByteBuffer payload]
  (when (= \s (char (take-ubyte payload)))
    (keyword (apply str (map char (repeatedly (.remaining payload)
                                              #(take-ubyte payload)))))))

(defn send-status-packet
  []
  (util/flip-pack 5 (str "sbbb") [3 (byte \s) (byte \o) (byte \k)]))

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

(defn send-challenge-packet
  [^String name challenge]
  (let [bytes (concat [(byte \n) version flag challenge]
                      (.getBytes name))
        len (+ 11 (count name))]
    (util/flip-pack (+ 2 len)
                    (str "sbsii" (apply str (repeat (count name) "b")))
                    (concat [len] bytes))))

(defn recv-challenge-reply-packet
  [^ByteBuffer payload]
  (when (= \r (char (take-ubyte payload)))
    {:challenge (take-uint payload)
     :digest (repeatedly 16 #(take-byte payload))}))

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

(defn send-challenge-ack-packet
  [challenge ^String cookie]
  (util/flip-pack 19 (str "sb" (apply str (repeat 16 "b")))
                  (concat [17 (byte \a)]
                          (util/digest challenge cookie))))

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
    (timbre/debug "PACKET:" raw-packet)
    (timbre/debug "HS-PACKET:" hs-packet)
    (timbre/debug "DECODED: " decoded)
    decoded))

(defn send-name [connection name]
  (tcp/send-bytes connection (send-name-packet name
                                               dflag-extended-pid-ports
                                               dflag-extended-references)))

(defn recv-name [connection]
  (read-handshake-packet connection receive-name-packet))

(defn send-status [connection]
  (tcp/send-bytes connection (send-status-packet)))

(defn recv-status [connection]
  (read-handshake-packet connection recv-status-packet))

(defn send-challenge [connection name challenge]
  (tcp/send-bytes connection (send-challenge-packet name challenge)))

(defn recv-challenge [connection]
  (read-handshake-packet connection recv-challenge-packet))

(defn gen-challenge-reply [b-challenge cookie]
  (send-challenge-reply-packet (:challenge b-challenge) cookie))

(defn send-challenge-reply [connection {:keys [payload] :as challenge}]
  (tcp/send-bytes connection payload))

(defn send-challenge-ack [connection challenge cookie]
  (tcp/send-bytes connection (send-challenge-ack-packet challenge cookie)))

(defn recv-challenge-reply [connection]
  (read-handshake-packet connection recv-challenge-reply-packet))

(defn check-challenge-ack [connection challenge cookie]
  (read-handshake-packet connection
                         (partial recv-challenge-ack-packet
                                  challenge cookie)))

;; this is for initiating a connection to a node
(defn do-handshake
  [{:keys [node-name cookie] :as node}
   {:keys [host port] :or {host "localhost"}
    :as other-node}]
  (let [port       (or port
                       (with-open [^SocketChannel epmd-conn
                                   (tcp/client "localhost" 4369)]
                         (epmd/port epmd-conn (util/plain-name
                                               (:node-name other-node)))))
        connection (tcp/client host port)]
    (send-name connection node-name)
    (recv-status connection)        ; should check status is ok,
                                        ; but not just now
    (let [b-challenge (recv-challenge connection)
          a-challenge (gen-challenge-reply b-challenge cookie)
          _           (send-challenge-reply connection a-challenge)
          ack         (check-challenge-ack connection
                                           (:challenge a-challenge)
                                           cookie)]
      [ack connection])))

;; this is for handling an incoming connection from a node
(defn handle-handshake
  [{:keys [node-name cookie] :as node}
   ^SocketChannel connection]
  (let [a-name      (recv-name connection)
        b-challenge (util/gen-challenge)
        result      {:other-node a-name :connection connection}]
    (timbre/debug "Connection from:" a-name)
    (send-status connection)
    (timbre/debug "Sent status :ok")
    (send-challenge connection node-name b-challenge)
    (timbre/debug "Sent challenge: " b-challenge)
    (let [{:keys [digest challenge]} (recv-challenge-reply connection)
          digest-matches?           (= digest
                                       (util/digest b-challenge cookie))]
      (timbre/debug "Challenge reply received")
      (timbre/debug "Digest matches: " digest-matches?)
      (if digest-matches?
        (do (send-challenge-ack connection challenge cookie)
            (timbre/debug "Sent challenge ack. Handhsake complete.")
            (assoc result :status :ok))
        (do (timbre/debug "Digest didn't match. Closing connection.")
            (.close connection)
            (assoc result :status :error))))))
