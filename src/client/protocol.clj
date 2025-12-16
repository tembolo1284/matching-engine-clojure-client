(ns client.protocol
  "Binary and CSV protocol encoder/decoder for matching engine."
  (:require [clojure.string :as str])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const MAGIC (int 0x4D))  ; 'M'

;; Message type bytes
(def ^:const TYPE-NEW-ORDER (byte (int \N)))
(def ^:const TYPE-CANCEL    (byte (int \C)))
(def ^:const TYPE-FLUSH     (byte (int \F)))
(def ^:const TYPE-ACK       (byte (int \A)))
(def ^:const TYPE-CANCEL-ACK (byte (int \X)))
(def ^:const TYPE-TRADE     (byte (int \T)))
(def ^:const TYPE-TOB       (byte (int \B)))
(def ^:const TYPE-REJECT    (byte (int \R)))

;; Message sizes
(def ^:const NEW-ORDER-SIZE 27)
(def ^:const CANCEL-SIZE    18)
(def ^:const FLUSH-SIZE     2)
(def ^:const ACK-SIZE       18)
(def ^:const TRADE-SIZE     34)
(def ^:const TOB-SIZE       20)
(def ^:const REJECT-SIZE    19)

(def ^:const SYMBOL-SIZE 8)

;; =============================================================================
;; Symbol Encoding
;; =============================================================================

(defn- symbol->bytes
  "Convert symbol string to 8-byte space-padded array."
  ^bytes [^String sym]
  (let [bs (.getBytes sym StandardCharsets/US_ASCII)
        out (byte-array SYMBOL-SIZE (byte 0x20))]  ; space-padded
    (System/arraycopy bs 0 out 0 (min (alength bs) SYMBOL-SIZE))
    out))

(defn- bytes->symbol
  "Convert 8-byte array to trimmed symbol string."
  [^bytes bs]
  (.trim (String. bs StandardCharsets/US_ASCII)))

;; =============================================================================
;; Binary Encoders (Input Messages)
;; =============================================================================

(defn encode-new-order
  "Encode a new order message (27 bytes).
   Returns: ByteBuffer ready to read"
  [user-id symbol price qty side order-id]
  (let [buf (ByteBuffer/allocate NEW-ORDER-SIZE)
        side-byte (if (= side :buy) (byte (int \B)) (byte (int \S)))]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.put buf (byte MAGIC))
    (.put buf TYPE-NEW-ORDER)
    (.putInt buf (int user-id))
    (.put buf (symbol->bytes symbol))
    (.putInt buf (int price))
    (.putInt buf (int qty))
    (.put buf side-byte)
    (.putInt buf (int order-id))
    (.flip buf)
    buf))

(defn encode-cancel
  "Encode a cancel order message (18 bytes).
   Returns: ByteBuffer ready to read"
  [user-id symbol order-id]
  (let [buf (ByteBuffer/allocate CANCEL-SIZE)]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.put buf (byte MAGIC))
    (.put buf TYPE-CANCEL)
    (.putInt buf (int user-id))
    (.put buf (symbol->bytes symbol))
    (.putInt buf (int order-id))
    (.flip buf)
    buf))

(defn encode-flush
  "Encode a flush command (2 bytes).
   Returns: ByteBuffer ready to read"
  []
  (let [buf (ByteBuffer/allocate FLUSH-SIZE)]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.put buf (byte MAGIC))
    (.put buf TYPE-FLUSH)
    (.flip buf)
    buf))

;; =============================================================================
;; Binary Decoders (Output Messages)
;; =============================================================================

(defn- decode-ack
  "Decode ACK message from buffer at current position."
  [^ByteBuffer buf]
  (let [sym-bytes (byte-array SYMBOL-SIZE)
        _ (.get buf sym-bytes)
        user-id (.getInt buf)
        order-id (.getInt buf)]
    {:type :ack
     :symbol (bytes->symbol sym-bytes)
     :user-id user-id
     :order-id order-id}))

(defn- decode-cancel-ack
  "Decode CANCEL_ACK message from buffer at current position."
  [^ByteBuffer buf]
  (let [sym-bytes (byte-array SYMBOL-SIZE)
        _ (.get buf sym-bytes)
        user-id (.getInt buf)
        order-id (.getInt buf)]
    {:type :cancel-ack
     :symbol (bytes->symbol sym-bytes)
     :user-id user-id
     :order-id order-id}))

(defn- decode-trade
  "Decode TRADE message from buffer at current position."
  [^ByteBuffer buf]
  (let [sym-bytes (byte-array SYMBOL-SIZE)
        _ (.get buf sym-bytes)
        buy-user-id (.getInt buf)
        buy-order-id (.getInt buf)
        sell-user-id (.getInt buf)
        sell-order-id (.getInt buf)
        price (.getInt buf)
        qty (.getInt buf)]
    {:type :trade
     :symbol (bytes->symbol sym-bytes)
     :buy-user-id buy-user-id
     :buy-order-id buy-order-id
     :sell-user-id sell-user-id
     :sell-order-id sell-order-id
     :price price
     :quantity qty}))

(defn- decode-tob
  "Decode TOP_OF_BOOK message from buffer at current position."
  [^ByteBuffer buf]
  (let [sym-bytes (byte-array SYMBOL-SIZE)
        _ (.get buf sym-bytes)
        side-byte (.get buf)
        price (.getInt buf)
        qty (.getInt buf)
        _ (.get buf)  ; padding
        side (case (int side-byte)
               66 :buy   ; 'B'
               83 :sell  ; 'S'
               nil)]
    {:type :top-of-book
     :symbol (bytes->symbol sym-bytes)
     :side side
     :price price
     :quantity qty
     :eliminated? (and (zero? price) (zero? qty))}))

(defn- decode-reject
  "Decode REJECT message from buffer at current position."
  [^ByteBuffer buf]
  (let [sym-bytes (byte-array SYMBOL-SIZE)
        _ (.get buf sym-bytes)
        user-id (.getInt buf)
        order-id (.getInt buf)
        reason (.get buf)]
    {:type :reject
     :symbol (bytes->symbol sym-bytes)
     :user-id user-id
     :order-id order-id
     :reason reason}))

(defn decode-output
  "Decode an output message from a ByteBuffer."
  [^ByteBuffer buf]
  (.order buf ByteOrder/BIG_ENDIAN)
  (when (< (.remaining buf) 2)
    (throw (ex-info "Message too short" {:remaining (.remaining buf)})))

  (let [magic (.get buf)
        msg-type (.get buf)]
    (when-not (= (bit-and magic 0xFF) MAGIC)
      (throw (ex-info "Invalid magic byte" {:expected MAGIC :got magic})))

    (condp = msg-type
      TYPE-ACK        (decode-ack buf)
      TYPE-CANCEL-ACK (decode-cancel-ack buf)
      TYPE-TRADE      (decode-trade buf)
      TYPE-TOB        (decode-tob buf)
      TYPE-REJECT     (decode-reject buf)
      (throw (ex-info "Unknown message type" {:type msg-type})))))

(defn decode-output-bytes
  "Decode an output message from a byte array."
  [^bytes bs]
  (decode-output (ByteBuffer/wrap bs)))

;; =============================================================================
;; CSV Encoders
;; =============================================================================

(defn csv-encode-new-order
  "Encode a new order as CSV line.
   Format: N,<user_id>,<symbol>,<price>,<quantity>,<side>,<order_id>\\n"
  [user-id symbol price qty side order-id]
  (let [side-char (if (= side :buy) "B" "S")]
    (.getBytes (format "N,%d,%s,%d,%d,%s,%d\n"
                       user-id symbol price qty side-char order-id)
               StandardCharsets/US_ASCII)))

(defn csv-encode-cancel
  "Encode a cancel as CSV line.
   Format: C,<user_id>,<symbol>,<order_id>\\n"
  [user-id symbol order-id]
  (.getBytes (format "C,%d,%s,%d\n" user-id symbol order-id)
             StandardCharsets/US_ASCII))

(defn csv-encode-flush
  "Encode a flush as CSV line.
   Format: F\\n"
  []
  (.getBytes "F\n" StandardCharsets/US_ASCII))

;; =============================================================================
;; CSV Decoders
;; =============================================================================

(defn- parse-int [^String s]
  (Integer/parseInt (str/trim s)))

(defn csv-decode-output
  "Decode a CSV output message line."
  [^String line]
  (let [line (str/trim line)
        parts (str/split line #",")]
    (when (seq parts)
      (case (first parts)
        "A" {:type :ack
             :symbol (nth parts 1)
             :user-id (parse-int (nth parts 2))
             :order-id (parse-int (nth parts 3))}

        "X" {:type :cancel-ack
             :symbol (nth parts 1)
             :user-id (parse-int (nth parts 2))
             :order-id (parse-int (nth parts 3))}

        "T" {:type :trade
             :symbol (nth parts 1)
             :buy-user-id (parse-int (nth parts 2))
             :buy-order-id (parse-int (nth parts 3))
             :sell-user-id (parse-int (nth parts 4))
             :sell-order-id (parse-int (nth parts 5))
             :price (parse-int (nth parts 6))
             :quantity (parse-int (nth parts 7))}

        "B" (let [price-str (nth parts 3)
                  qty-str (nth parts 4)
                  price (if (= price-str "-") 0 (parse-int price-str))
                  qty (if (= qty-str "-") 0 (parse-int qty-str))]
              {:type :top-of-book
               :symbol (nth parts 1)
               :side (case (nth parts 2) "B" :buy "S" :sell nil)
               :price price
               :quantity qty
               :eliminated? (and (zero? price) (zero? qty))})

        "R" {:type :reject
             :symbol (nth parts 1)
             :user-id (parse-int (nth parts 2))
             :order-id (parse-int (nth parts 3))
             :reason (when (> (count parts) 4) (parse-int (nth parts 4)))}

        nil))))

(defn csv-decode-output-bytes
  "Decode CSV output from byte array."
  [^bytes bs]
  (csv-decode-output (String. bs StandardCharsets/US_ASCII)))

;; =============================================================================
;; Auto-Detection
;; =============================================================================

(defn binary-message?
  "Check if data looks like a binary message (starts with magic byte)."
  [^bytes data]
  (and (pos? (alength data))
       (= (bit-and (aget data 0) 0xFF) MAGIC)))

(defn decode-auto
  "Auto-detect format and decode message."
  [^bytes data]
  (if (binary-message? data)
    (decode-output-bytes data)
    (csv-decode-output-bytes data)))

;; =============================================================================
;; Message Formatting (for REPL display)
;; =============================================================================

(defn format-price
  "Format price as string."
  [price]
  (str price))

(defn format-message
  "Format a decoded message for human-readable display."
  [msg]
  (case (:type msg)
    :ack
    (format "A, %s, %d, %d"
            (:symbol msg) (:user-id msg) (:order-id msg))

    :cancel-ack
    (format "C, %s, %d, %d"
            (:symbol msg) (:user-id msg) (:order-id msg))

    :trade
    (format "T, %s, %d, %d, %d, %d, %s, %d"
            (:symbol msg)
            (:buy-user-id msg) (:buy-order-id msg)
            (:sell-user-id msg) (:sell-order-id msg)
            (format-price (:price msg))
            (:quantity msg))

    :top-of-book
    (if (:eliminated? msg)
      (format "B, %s, %s, -, -"
              (:symbol msg)
              (if (:side msg) (if (= (:side msg) :buy) "B" "S") "-"))
      (format "B, %s, %s, %s, %d"
              (:symbol msg)
              (if (= (:side msg) :buy) "B" "S")
              (format-price (:price msg))
              (:quantity msg)))

    :reject
    (format "R, %s, %d, %d, reason=%d"
            (:symbol msg) (:user-id msg) (:order-id msg) (:reason msg))

    :parse-error
    (format "PARSE ERROR: %s" (:error msg))

    ;; Default case
    (str msg)))
