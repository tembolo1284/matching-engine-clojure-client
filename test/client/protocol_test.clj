(ns client.protocol-test
  (:require [clojure.test :refer [deftest testing is]]
            [client.protocol :as proto])
  (:import [java.nio ByteBuffer ByteOrder]))

;; =============================================================================
;; Symbol Encoding Tests
;; =============================================================================

(deftest symbol-encoding-test
  (testing "Symbol to bytes"
    (let [bs (proto/symbol->bytes "IBM")]
      (is (= 8 (alength bs)))
      (is (= (byte (int \I)) (aget bs 0)))
      (is (= (byte (int \B)) (aget bs 1)))
      (is (= (byte (int \M)) (aget bs 2)))
      (is (= 0 (aget bs 3)))))
  
  (testing "Bytes to symbol"
    (let [bs (byte-array [73 66 77 0 0 0 0 0])]  ; "IBM" + nulls
      (is (= "IBM" (proto/bytes->symbol bs))))
    
    (let [bs (byte-array [65 65 80 76 0 0 0 0])]  ; "AAPL"
      (is (= "AAPL" (proto/bytes->symbol bs))))))

;; =============================================================================
;; Binary Encoding Tests
;; =============================================================================

(deftest encode-new-order-test
  (testing "Binary new order encoding"
    (let [buf (proto/encode-new-order 1 "IBM" 10000 50 :buy 1001)]
      (is (= 27 (.remaining buf)))
      (is (= proto/MAGIC (.get buf)))
      (is (= proto/TYPE-NEW-ORDER (.get buf))))))

(deftest encode-cancel-test
  (testing "Binary cancel encoding"
    (let [buf (proto/encode-cancel 1 "IBM" 1001)]
      (is (= 18 (.remaining buf)))
      (is (= proto/MAGIC (.get buf)))
      (is (= proto/TYPE-CANCEL (.get buf))))))

(deftest encode-flush-test
  (testing "Binary flush encoding"
    (let [buf (proto/encode-flush)]
      (is (= 2 (.remaining buf)))
      (is (= proto/MAGIC (.get buf)))
      (is (= proto/TYPE-FLUSH (.get buf))))))

;; =============================================================================
;; Binary Decoding Tests
;; =============================================================================

(deftest decode-ack-test
  (testing "Decode binary ACK message"
    (let [buf (ByteBuffer/allocate 18)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.put buf (byte proto/MAGIC))
      (.put buf proto/TYPE-ACK)
      (.put buf (proto/symbol->bytes "IBM"))
      (.putInt buf 1)      ; user-id
      (.putInt buf 1001)   ; order-id
      (.flip buf)
      
      (let [msg (proto/decode-output buf)]
        (is (= :ack (:type msg)))
        (is (= "IBM" (:symbol msg)))
        (is (= 1 (:user-id msg)))
        (is (= 1001 (:order-id msg)))))))

(deftest decode-trade-test
  (testing "Decode binary TRADE message"
    (let [buf (ByteBuffer/allocate 34)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.put buf (byte proto/MAGIC))
      (.put buf proto/TYPE-TRADE)
      (.put buf (proto/symbol->bytes "AAPL"))
      (.putInt buf 1)       ; buy-user-id
      (.putInt buf 100)     ; buy-order-id
      (.putInt buf 2)       ; sell-user-id
      (.putInt buf 200)     ; sell-order-id
      (.putInt buf 15000)   ; price
      (.putInt buf 50)      ; quantity
      (.flip buf)
      
      (let [msg (proto/decode-output buf)]
        (is (= :trade (:type msg)))
        (is (= "AAPL" (:symbol msg)))
        (is (= 1 (:buy-user-id msg)))
        (is (= 100 (:buy-order-id msg)))
        (is (= 2 (:sell-user-id msg)))
        (is (= 200 (:sell-order-id msg)))
        (is (= 15000 (:price msg)))
        (is (= 50 (:quantity msg)))))))

(deftest decode-tob-test
  (testing "Decode binary TOP_OF_BOOK message"
    (let [buf (ByteBuffer/allocate 20)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.put buf (byte proto/MAGIC))
      (.put buf proto/TYPE-TOB)
      (.put buf (proto/symbol->bytes "IBM"))
      (.put buf (byte (int \B)))  ; side
      (.putInt buf 10000)         ; price
      (.putInt buf 100)           ; quantity
      (.put buf (byte 0))         ; padding
      (.flip buf)
      
      (let [msg (proto/decode-output buf)]
        (is (= :top-of-book (:type msg)))
        (is (= "IBM" (:symbol msg)))
        (is (= :buy (:side msg)))
        (is (= 10000 (:price msg)))
        (is (= 100 (:quantity msg)))
        (is (not (:eliminated? msg)))))))

;; =============================================================================
;; CSV Encoding Tests
;; =============================================================================

(deftest csv-encode-new-order-test
  (testing "CSV new order encoding"
    (let [bs (proto/csv-encode-new-order 1 "IBM" 10000 50 :buy 1001)
          s (String. bs)]
      (is (= "N,1,IBM,10000,50,B,1001\n" s)))))

(deftest csv-encode-cancel-test
  (testing "CSV cancel encoding"
    (let [bs (proto/csv-encode-cancel 1 "IBM" 1001)
          s (String. bs)]
      (is (= "C,1,IBM,1001\n" s)))))

(deftest csv-encode-flush-test
  (testing "CSV flush encoding"
    (let [bs (proto/csv-encode-flush)
          s (String. bs)]
      (is (= "F\n" s)))))

;; =============================================================================
;; CSV Decoding Tests
;; =============================================================================

(deftest csv-decode-ack-test
  (testing "Decode CSV ACK"
    (let [msg (proto/csv-decode-output "A,IBM,1,1001")]
      (is (= :ack (:type msg)))
      (is (= "IBM" (:symbol msg)))
      (is (= 1 (:user-id msg)))
      (is (= 1001 (:order-id msg))))))

(deftest csv-decode-cancel-ack-test
  (testing "Decode CSV CANCEL_ACK"
    (let [msg (proto/csv-decode-output "X,IBM,1,1001")]
      (is (= :cancel-ack (:type msg)))
      (is (= "IBM" (:symbol msg)))
      (is (= 1 (:user-id msg)))
      (is (= 1001 (:order-id msg))))))

(deftest csv-decode-trade-test
  (testing "Decode CSV TRADE"
    (let [msg (proto/csv-decode-output "T,AAPL,1,100,2,200,15000,50")]
      (is (= :trade (:type msg)))
      (is (= "AAPL" (:symbol msg)))
      (is (= 1 (:buy-user-id msg)))
      (is (= 100 (:buy-order-id msg)))
      (is (= 2 (:sell-user-id msg)))
      (is (= 200 (:sell-order-id msg)))
      (is (= 15000 (:price msg)))
      (is (= 50 (:quantity msg))))))

(deftest csv-decode-tob-test
  (testing "Decode CSV TOP_OF_BOOK"
    (let [msg (proto/csv-decode-output "B,IBM,B,10000,100")]
      (is (= :top-of-book (:type msg)))
      (is (= "IBM" (:symbol msg)))
      (is (= :buy (:side msg)))
      (is (= 10000 (:price msg)))
      (is (= 100 (:quantity msg)))
      (is (not (:eliminated? msg)))))
  
  (testing "Decode eliminated book"
    (let [msg (proto/csv-decode-output "B,IBM,B,0,0")]
      (is (= :top-of-book (:type msg)))
      (is (:eliminated? msg)))))

;; =============================================================================
;; Protocol Detection Tests
;; =============================================================================

(deftest binary-message-detection-test
  (testing "Binary message detection"
    (is (proto/binary-message? (byte-array [0x4D 0x41 0 0 0])))
    (is (not (proto/binary-message? (byte-array [0x41 0x2C 0x49]))))  ; "A,I..."
    (is (not (proto/binary-message? (byte-array []))))))

(deftest decode-auto-test
  (testing "Auto-detect binary"
    (let [buf (ByteBuffer/allocate 18)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.put buf (byte proto/MAGIC))
      (.put buf proto/TYPE-ACK)
      (.put buf (proto/symbol->bytes "IBM"))
      (.putInt buf 1)
      (.putInt buf 1001)
      (.flip buf)
      (let [arr (byte-array 18)]
        (.get buf arr)
        (let [msg (proto/decode-auto arr)]
          (is (= :ack (:type msg)))))))
  
  (testing "Auto-detect CSV"
    (let [arr (.getBytes "A,IBM,1,1001")]
      (let [msg (proto/decode-auto arr)]
        (is (= :ack (:type msg)))))))

;; =============================================================================
;; Formatting Tests
;; =============================================================================

(deftest format-price-test
  (testing "Price formatting"
    (is (= "$100.00" (proto/format-price 10000)))
    (is (= "$1.50" (proto/format-price 150)))
    (is (= "$0.01" (proto/format-price 1)))))

(deftest format-message-test
  (testing "ACK message formatting"
    (let [s (proto/format-message {:type :ack :symbol "IBM" :user-id 1 :order-id 1})]
      (is (string? s))
      (is (.contains s "ACK"))
      (is (.contains s "IBM"))))
  
  (testing "Trade message formatting"
    (let [s (proto/format-message {:type :trade :symbol "IBM" :price 10000 :quantity 50
                                   :buy-user-id 1 :buy-order-id 1
                                   :sell-user-id 2 :sell-order-id 2})]
      (is (string? s))
      (is (.contains s "TRADE"))
      (is (.contains s "$100.00")))))
