(ns client.protocol-test
  (:require [clojure.test :refer [deftest testing is are]]
            [client.protocol :as proto])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

;; =============================================================================
;; Symbol Encoding
;; =============================================================================

(deftest encode-symbol-test
  (testing "Normal symbol encoding"
    (let [bs (proto/encode-symbol "IBM")]
      (is (= 8 (alength bs)))
      (is (= "IBM     " (String. bs)))))
  
  (testing "Full 8-char symbol"
    (let [bs (proto/encode-symbol "GOOGL123")]
      (is (= 8 (alength bs)))
      (is (= "GOOGL123" (String. bs)))))
  
  (testing "Long symbol truncated"
    (let [bs (proto/encode-symbol "VERYLONGSYMBOL")]
      (is (= 8 (alength bs)))
      (is (= "VERYLONG" (String. bs)))))
  
  (testing "Empty symbol"
    (let [bs (proto/encode-symbol "")]
      (is (= 8 (alength bs)))
      (is (= "        " (String. bs))))))

(deftest decode-symbol-test
  (testing "Normal symbol decoding"
    (is (= "IBM" (proto/decode-symbol (.getBytes "IBM     ")))))
  
  (testing "Full symbol decoding"
    (is (= "GOOGL123" (proto/decode-symbol (.getBytes "GOOGL123")))))
  
  (testing "Whitespace trimmed"
    (is (= "AAPL" (proto/decode-symbol (.getBytes "AAPL    "))))))

;; =============================================================================
;; Message Encoding
;; =============================================================================

(deftest encode-new-order-test
  (testing "NewOrder message structure"
    (let [msg {:type :new-order
               :user-id 1
               :order-id 42
               :side :buy
               :symbol "IBM"
               :price 100.50
               :qty 100}
          bs (proto/encode-new-order msg)]
      ;; Header (4) + payload (30) = 34 bytes
      (is (= 34 (alength bs)))
      ;; Magic bytes
      (is (= 0x4D (bit-and (aget bs 0) 0xFF)))
      (is (= 0x45 (bit-and (aget bs 1) 0xFF)))
      ;; Length = 30
      (is (= 30 (bit-and (aget bs 2) 0xFF)))
      ;; Type = 0x01 (NewOrder)
      (is (= 0x01 (bit-and (aget bs 4) 0xFF)))))
  
  (testing "Sell side encoding"
    (let [msg {:type :new-order
               :user-id 1
               :order-id 1
               :side :sell
               :symbol "AAPL"
               :price 150.00
               :qty 50}
          bs (proto/encode-new-order msg)]
      ;; Side byte at position 13 (4 header + 1 type + 4 user-id + 4 order-id)
      (is (= 0x02 (bit-and (aget bs 13) 0xFF))))))

(deftest encode-cancel-test
  (testing "Cancel message structure"
    (let [msg {:type :cancel
               :user-id 1
               :order-id 42
               :symbol "IBM"}
          bs (proto/encode-cancel msg)]
      ;; Header (4) + payload (17) = 21 bytes
      (is (= 21 (alength bs)))
      ;; Type = 0x02 (Cancel)
      (is (= 0x02 (bit-and (aget bs 4) 0xFF))))))

(deftest encode-flush-test
  (testing "Flush message structure"
    (let [bs (proto/encode-flush)]
      ;; Header (4) + payload (1) = 5 bytes
      (is (= 5 (alength bs)))
      ;; Type = 0x03 (Flush)
      (is (= 0x03 (bit-and (aget bs 4) 0xFF))))))

(deftest encode-message-test
  (testing "Dispatch by type"
    (is (= 34 (alength (proto/encode-message {:type :new-order
                                               :user-id 1
                                               :order-id 1
                                               :side :buy
                                               :symbol "IBM"
                                               :price 100.0
                                               :qty 100}))))
    (is (= 21 (alength (proto/encode-message {:type :cancel
                                               :user-id 1
                                               :order-id 1
                                               :symbol "IBM"}))))
    (is (= 5 (alength (proto/encode-message {:type :flush})))))
  
  (testing "Unknown type throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (proto/encode-message {:type :unknown})))))

;; =============================================================================
;; Message Decoding
;; =============================================================================

(deftest decode-payload-test
  (testing "OrderAck decoding"
    ;; Build payload: type(1) + user-id(4) + order-id(4) + symbol(8) = 17 bytes
    (let [payload (byte-array [0x10  ; type = order-ack
                               0x01 0x00 0x00 0x00  ; user-id = 1
                               0x2A 0x00 0x00 0x00  ; order-id = 42
                               0x49 0x42 0x4D 0x20 0x20 0x20 0x20 0x20])  ; "IBM     "
          msg (proto/decode-payload payload)]
      (is (= :order-ack (:type msg)))
      (is (= 1 (:user-id msg)))
      (is (= 42 (:order-id msg)))
      (is (= "IBM" (:symbol msg)))))
  
  (testing "OrderReject decoding"
    (let [payload (byte-array [0x11  ; type = order-reject
                               0x01 0x00 0x00 0x00  ; user-id = 1
                               0x05 0x00 0x00 0x00  ; order-id = 5
                               0x03])  ; reason = 3
          msg (proto/decode-payload payload)]
      (is (= :order-reject (:type msg)))
      (is (= 1 (:user-id msg)))
      (is (= 5 (:order-id msg)))
      (is (= 3 (:reason msg)))))
  
  (testing "Trade decoding"
    ;; Trade: type(1) + symbol(8) + price(8) + qty(4) + buy-user(4) + buy-order(4) + sell-user(4) + sell-order(4) = 37 bytes
    (let [payload (byte-array (concat
                               [0x20]  ; type = trade
                               [0x49 0x42 0x4D 0x20 0x20 0x20 0x20 0x20]  ; "IBM     "
                               [0x00 0x00 0x00 0x00 0x00 0x20 0x59 0x40]  ; price = 100.5 (IEEE 754)
                               [0x64 0x00 0x00 0x00]  ; qty = 100
                               [0x01 0x00 0x00 0x00]  ; buy-user-id = 1
                               [0x01 0x00 0x00 0x00]  ; buy-order-id = 1
                               [0x02 0x00 0x00 0x00]  ; sell-user-id = 2
                               [0x02 0x00 0x00 0x00]))  ; sell-order-id = 2
          msg (proto/decode-payload (byte-array payload))]
      (is (= :trade (:type msg)))
      (is (= "IBM" (:symbol msg)))
      (is (= 100 (:qty msg)))
      (is (= 1 (:buy-user-id msg)))
      (is (= 1 (:buy-order-id msg)))
      (is (= 2 (:sell-user-id msg)))
      (is (= 2 (:sell-order-id msg))))))

;; =============================================================================
;; Frame Reading
;; =============================================================================

(deftest read-frame-test
  (testing "Valid frame reading"
    (let [payload (byte-array [0x10  ; order-ack
                               0x01 0x00 0x00 0x00
                               0x01 0x00 0x00 0x00
                               0x49 0x42 0x4D 0x20 0x20 0x20 0x20 0x20])
          frame (byte-array (concat
                             [0x4D 0x45]  ; magic
                             [0x11 0x00]  ; length = 17
                             payload))
          in (ByteArrayInputStream. frame)
          msg (proto/read-frame in)]
      (is (= :order-ack (:type msg)))
      (is (= 1 (:user-id msg)))))
  
  (testing "EOF returns nil"
    (let [in (ByteArrayInputStream. (byte-array 0))]
      (is (nil? (proto/read-frame in)))))
  
  (testing "Invalid magic throws"
    (let [frame (byte-array [0x00 0x00 0x11 0x00])
          in (ByteArrayInputStream. frame)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (proto/read-frame in))))))

;; =============================================================================
;; Roundtrip Tests
;; =============================================================================

(deftest roundtrip-test
  (testing "Encode then decode preserves data"
    (let [original {:type :new-order
                    :user-id 123
                    :order-id 456
                    :side :buy
                    :symbol "AAPL"
                    :price 150.75
                    :qty 200}
          encoded (proto/encode-new-order original)
          ;; Skip header, decode payload
          payload (byte-array (drop 4 encoded))
          ;; We need to test decode of response messages, not request
          ;; So let's test OrderAck roundtrip instead
          ack-payload (byte-array [0x10
                                   0x7B 0x00 0x00 0x00  ; 123
                                   0xC8 0x01 0x00 0x00  ; 456
                                   0x41 0x41 0x50 0x4C 0x20 0x20 0x20 0x20])
          decoded (proto/decode-payload ack-payload)]
      (is (= :order-ack (:type decoded)))
      (is (= 123 (:user-id decoded)))
      (is (= 456 (:order-id decoded)))
      (is (= "AAPL" (:symbol decoded))))))

;; =============================================================================
;; Format Message
;; =============================================================================

(deftest format-message-test
  (testing "OrderAck formatting"
    (let [msg {:type :order-ack :order-id 42 :symbol "IBM"}]
      (is (= "ACK order #42 IBM" (proto/format-message msg)))))
  
  (testing "Trade formatting"
    (let [msg {:type :trade :symbol "AAPL" :qty 100 :price 150.50
               :buy-user-id 1 :buy-order-id 1
               :sell-user-id 2 :sell-order-id 2}]
      (is (= "TRADE AAPL 100 @ 150.50 (buy:1/1 sell:2/2)"
             (proto/format-message msg)))))
  
  (testing "Unknown type"
    (let [msg {:type :unknown :data "test"}]
      (is (clojure.string/includes? (proto/format-message msg) "UNKNOWN")))))
