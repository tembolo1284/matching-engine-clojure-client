(ns client.protocol
  "Binary and CSV protocol encoder/decoder for matching engine.

   Binary wire format uses big-endian byte ordering.
   All binary messages start with magic byte 0x4D ('M').
   CSV messages are newline-terminated text."
  (:require [clojure.string :as str])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const MAGIC 0x4D)
(def ^:const SYMBOL-SIZE 8)

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

;; =============================================================================
;; Symbol Encoding
;; =============================================================================

(defn symbol->bytes
  "Convert symbol string to 8-byte null-padded array."
  ^bytes [^String sym]
  (let [bs (byte-array SYMBOL-SIZE)
        src (.getBytes sym StandardCharsets/US_ASCII)
        len (min (alength src) SYMBOL-SIZE)]
    (System/arraycopy src 0 bs 0 len)
    bs))

(defn bytes->symbol
  "Convert 8-byte array to symbol string (strips nulls)."
  ^String [^bytes bs]
  (let [len (loop [i 0]
              (if (or (>= i SYMBOL-SIZE) (zero? (aget bs i)))
                i
                (recur (inc i))))]
    (String. bs 0 len StandardCharsets/US_ASCII)))

;; =============================================================================
;; Input Message Encoding (Client → Server)
;; =============================================================================

(defn encode-new-order
  "Encode a new order message (27 bytes).

   Args:
     user-id: User identifier (int)
     symbol: Stock symbol (string, max 8 chars)
     price: Price (int)
     qty: Quantity (int)
     side: :buy or :sell
     order-id: Order identifier (int)

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

   Args:
     user-id: User identifier
     symbol: Stock symbol
     order-id: Order to cancel

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
  "Encode a flush message (2 bytes).
   Returns: ByteBuffer ready to read"
  []
  (let [buf (ByteBuffer/allocate FLUSH-SIZE)]
    (.put buf (byte MAGIC))
    (.put buf TYPE-FLUSH)
    (.flip buf)
    buf))

;; =============================================================================
;; Output Message Decoding (Server → Client)
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
  "Decode TOP_OF_BOOK message from buffer at current position.
   Wire format: symbol(8) + side(1) + price(4) + qty(4) + pad(1) = 18 bytes after header"
  [^ByteBuffer buf]
  (let [sym-bytes (byte-array SYMBOL-SIZE)
        _ (.get buf sym-bytes)
        side-byte (.get buf)
        price (.getInt buf)
        qty (.getInt buf)
        _ (.get buf) ; padding at END
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
  "Decode an output message from a ByteBuffer.

   Args:
     buf: ByteBuffer positioned at start of message (after frame length)

   Returns: Map with :type and message-specific fields
   Throws: ExceptionInfo on decode error"
  [^ByteBuffer buf]
  (.order buf ByteOrder/BIG_ENDIAN)
  (when (< (.remaining buf) 2)
    (throw (ex-info "Message too short" {:remaining (.remaining buf)})))

  (let [magic (.get buf)
        msg-type (.get buf)]
    (when-not (= magic (byte MAGIC))
      (throw (ex-info "Invalid magic byte" {:expected MAGIC :got magic})))

    (condp = msg-type
      TYPE-ACK        (decode-ack buf)
      TYPE-CANCEL-ACK (decode-cancel-ack buf)
      TYPE-TRADE      (decode-trade buf)
      TYPE-TOB        (decode-tob buf)
      TYPE-REJECT     (decode-reject buf)
      (throw (ex-info "Unknown message type" {:type msg-type})))))

(defn decode-output-bytes
  "Decode an output message from a byte array.

   Args:
     bs: byte array containing the message

   Returns: Map with :type and message-specific fields"
  [^bytes bs]
  (decode-output (ByteBuffer/wrap bs)))

;; =============================================================================
;; Message Formatting (for REPL display)
;; =============================================================================

(defn format-price
  "Format price as decimal string."
  [price]
  (format "%.2f" (double price)))

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

    ;; Default case
    (str msg)))

;; =============================================================================
;; CSV Protocol Encoding (Client → Server)
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
  "Encode a cancel order as CSV line.
   Format: C,<user_id>,<symbol>,<order_id>\\n"
  [user-id symbol order-id]
  (.getBytes (format "C,%d,%s,%d\n" user-id symbol order-id)
             StandardCharsets/US_ASCII))

(defn csv-encode-flush
  "Encode a flush command as CSV line.
   Format: F\\n"
  []
  (.getBytes "F\n" StandardCharsets/US_ASCII))

;; =============================================================================
;; CSV Protocol Decoding (Server → Client)
;; =============================================================================

(defn- parse-int [^String s]
  (Integer/parseInt (str/trim s)))

(defn csv-decode-output
  "Decode a CSV output message line.

   Formats:
     A,<symbol>,<user_id>,<order_id>
     X,<symbol>,<user_id>,<order_id>
     T,<symbol>,<buy_uid>,<buy_oid>,<sell_uid>,<sell_oid>,<price>,<qty>
     B,<symbol>,<side>,<price>,<qty>
     R,<symbol>,<user_id>,<order_id>,<reason>"
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

        "B" (let [price (parse-int (nth parts 3))
                  qty (parse-int (nth parts 4))]
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
;; Protocol Detection
;; =============================================================================

(defn binary-message?
  "Check if byte array starts with binary magic byte."
  [^bytes bs]
  (and (pos? (alength bs))
       (= (aget bs 0) (byte MAGIC))))

(defn decode-auto
  "Auto-detect protocol and decode message.
   Returns map with :type and message fields."
  [^bytes bs]
  (if (binary-message? bs)
    (decode-output-bytes bs)
    (csv-decode-output-bytes bs)))
