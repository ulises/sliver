(ns sliver.protocol-test
  (:require [bytebuffer.buff :refer [take-uint]]
            [clojure.test :refer :all]
            [sliver.protocol :refer :all])
  (:import [java.nio ByteBuffer]))

(defonce raw-bytes (ByteBuffer/wrap
                    (byte-array '(0 0 0 51 112 131 104 4 97 6 103 100 0 13 102
                                    111 111 64 49 50 55 46 48 46 48 46 49 0 0 0
                                    38 0 0 0 0 3 100 0 0 100 0 7 110 111 101 120
                                    105 115 116 131 100 0 2 104 105))))

(deftest test-read-pass-through-packet
  (is (= (ByteBuffer/wrap (byte-array '(131 104 4 97 6 103 100 0 13 102 111 111
                                            64 49 50 55 46 48 46 48 46 49 0 0 0
                                            38 0 0 0 0 3 100 0 0 100 0 7 110 111
                                            101 120 105 115 116 131 100 0 2 104
                                            105)))
         (read-pass-through-packet raw-bytes))))
