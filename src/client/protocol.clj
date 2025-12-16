(ns client.protocol
<<<<<<< HEAD
  "Binary protocol encoder/decoder for matching engine communication.
   
   Wire format (little-endian):
   ┌──────────┬──────────┬─────────────────┐
   │ Magic    │ Length   │ Payload         │
   │ 2 bytes  │ 2 bytes  │ N bytes         │
   │ 0x4D45   │ uint16   │ (type+fields)   │
   └──────────┴──────────┴─────────────────┘
   
   Shared by REPL client, relay, and any JVM consumer."
=======
  "Binary and CSV protocol encoder/decoder for matching engine.

   Binary wire format uses big-endian byte ordering.
   All binary messages start with magic byte 0x4D ('M').
   CSV messages are newline-terminated text."
  (:require [clojure.string :as str])
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Constants
;; =============================================================================

(def magic-bytes (byte-array [(unchecked-byte 0x4D) (unchecked-byte 0x45)]))

<<<<<<< HEAD
(def message-types
  {:new-order     0x01
   :cancel        0x02
   :flush         0x03
   :order-ack     0x10
   :order-reject  0x11
   :cancel-ack    0x12
   :cancel-reject 0x13
   :trade         0x20
   :book-update   0x21})

(def type->keyword
  (into {} (map (fn [[k v]] [v k]) message-types)))

(def sides
  {:buy  0x01
   :sell 0x02})

(def side->keyword
  {0x01 :buy
   0x02 :sell})
=======
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
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f

;; =============================================================================
;; Symbol Encoding
;; =============================================================================

(defn encode-symbol
  "Encode symbol to 8-byte space-padded array."
  ^bytes [^String sym]
  (let [bs (.getBytes sym StandardCharsets/US_ASCII)
        out (byte-array 8 (byte 0x20))]  ; space-padded
    (System/arraycopy bs 0 out 0 (min (alength bs) 8))
    out))

(defn decode-symbol
  "Decode 8-byte symbol, trimming trailing spaces."
  [^bytes bs]
  (.trim (String. bs StandardCharsets/US_ASCII)))

;; =============================================================================
;; Buffer Helpers
;; =============================================================================

(defn- make-buffer
  ^ByteBuffer [size]
  (-> (ByteBuffer/allocate size)
      (.order ByteOrder/LITTLE_ENDIAN)))

(defn- put-header!
  "Write magic + length placeholder. Returns buffer positioned after header."
  ^ByteBuffer [^ByteBuffer buf]
  (.put buf magic-bytes)
  (.putShort buf (short 0))  ; placeholder
  buf)

(defn- finalize-length!
  "Backpatch the length field and flip buffer for reading."
  ^ByteBuffer [^ByteBuffer buf]
  (let [len (- (.position buf) 4)]
    (.putShort buf 2 (short len))
    (.flip buf)
    buf))

;; =============================================================================
;; Encoders
;; =============================================================================

(defn encode-new-order
<<<<<<< HEAD
  "Encode NewOrder message.
   Fields: type(1) + user-id(4) + order-id(4) + side(1) + symbol(8) + price(8) + qty(4) = 30 bytes"
  [{:keys [user-id order-id side symbol price qty]}]
  (-> (make-buffer 34)
      (put-header!)
      (.put (byte (message-types :new-order)))
      (.putInt (int user-id))
      (.putInt (int order-id))
      (.put (byte (sides side)))
      (.put (encode-symbol symbol))
      (.putDouble (double price))
      (.putInt (int qty))
      (finalize-length!)
      (.array)))

(defn encode-cancel
  "Encode Cancel message.
   Fields: type(1) + user-id(4) + order-id(4) + symbol(8) = 17 bytes"
  [{:keys [user-id order-id symbol]}]
  (-> (make-buffer 21)
      (put-header!)
      (.put (byte (message-types :cancel)))
      (.putInt (int user-id))
      (.putInt (int order-id))
      (.put (encode-symbol symbol))
      (finalize-length!)
      (.array)))
=======
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
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f

(defn encode-flush
  "Encode Flush message.
   Fields: type(1) = 1 byte"
  []
  (-> (make-buffer 5)
      (put-header!)
      (.put (byte (message-types :flush)))
      (finalize-length!)
      (.array)))

<<<<<<< HEAD
(defn encode-message
  "Encode any outbound message by type."
  [msg]
  (case (:type msg)
    :new-order (encode-new-order msg)
    :cancel    (encode-cancel msg)
    :flush     (encode-flush)
    (throw (ex-info "Unknown message type" {:msg msg}))))
=======
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
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f

;; =============================================================================
;; Decoders
;; =============================================================================

<<<<<<< HEAD
(defn- decode-order-ack
  [^ByteBuffer buf]
  {:type     :order-ack
   :user-id  (.getInt buf)
   :order-id (.getInt buf)
   :symbol   (let [bs (byte-array 8)] (.get buf bs) (decode-symbol bs))})
=======
(defn csv-encode-new-order
  "Encode a new order as CSV line.
   Format: N,<user_id>,<symbol>,<price>,<quantity>,<side>,<order_id>\\n"
  [user-id symbol price qty side order-id]
  (let [side-char (if (= side :buy) "B" "S")]
    (.getBytes (format "N,%d,%s,%d,%d,%s,%d\n"
                       user-id symbol price qty side-char order-id)
               StandardCharsets/US_ASCII)))
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f

(defn- decode-order-reject
  [^ByteBuffer buf]
  {:type     :order-reject
   :user-id  (.getInt buf)
   :order-id (.getInt buf)
   :reason   (.get buf)})

(defn- decode-cancel-ack
  [^ByteBuffer buf]
  {:type     :cancel-ack
   :user-id  (.getInt buf)
   :order-id (.getInt buf)
   :symbol   (let [bs (byte-array 8)] (.get buf bs) (decode-symbol bs))})

(defn- decode-cancel-reject
  [^ByteBuffer buf]
  {:type     :cancel-reject
   :user-id  (.getInt buf)
   :order-id (.getInt buf)
   :reason   (.get buf)})

(defn- decode-trade
  [^ByteBuffer buf]
  {:type         :trade
   :symbol       (let [bs (byte-array 8)] (.get buf bs) (decode-symbol bs))
   :price        (.getDouble buf)
   :qty          (.getInt buf)
   :buy-user-id  (.getInt buf)
   :buy-order-id (.getInt buf)
   :sell-user-id  (.getInt buf)
   :sell-order-id (.getInt buf)})

(defn- decode-book-update
  [^ByteBuffer buf]
  {:type     :book-update
   :symbol   (let [bs (byte-array 8)] (.get buf bs) (decode-symbol bs))
   :side     (side->keyword (.get buf))
   :price    (.getDouble buf)
   :qty      (.getInt buf)})

(defn decode-payload
  "Decode a payload buffer (after magic/length stripped) into a message map."
  [^bytes payload]
  (let [buf (-> (ByteBuffer/wrap payload)
                (.order ByteOrder/LITTLE_ENDIAN))
        msg-type (type->keyword (.get buf))]
    (case msg-type
      :order-ack     (decode-order-ack buf)
      :order-reject  (decode-order-reject buf)
      :cancel-ack    (decode-cancel-ack buf)
      :cancel-reject (decode-cancel-reject buf)
      :trade         (decode-trade buf)
      :book-update   (decode-book-update buf)
      {:type :unknown :raw payload})))

;; =============================================================================
;; Framing
;; =============================================================================

<<<<<<< HEAD
(defn read-frame
  "Read one framed message from input stream. Returns decoded message or nil on EOF.
   Validates magic bytes. Throws on protocol violation."
  [^java.io.InputStream in]
  (let [header (byte-array 4)
        n (.read in header)]
    (cond
      (= n -1) nil
      (< n 4)  (throw (ex-info "Incomplete header" {:read n}))
      :else
      (let [magic-ok? (and (= (aget header 0) (unchecked-byte 0x4D))
                          (= (aget header 1) (unchecked-byte 0x45)))]
        (when-not magic-ok?
          (throw (ex-info "Invalid magic bytes" {:got [(aget header 0) (aget header 1)]})))
        (let [len (bit-or (bit-and (aget header 2) 0xFF)
                         (bit-shift-left (bit-and (aget header 3) 0xFF) 8))
              payload (byte-array len)
              payload-read (.read in payload)]
          (when (< payload-read len)
            (throw (ex-info "Incomplete payload" {:expected len :got payload-read})))
          (decode-payload payload))))))
=======
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
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f

;; =============================================================================
;; Formatting (for display)
;; =============================================================================

(defn format-message
  "Human-readable message string."
  [{:keys [type] :as msg}]
  (case type
    :order-ack     (format "ACK order #%d %s" (:order-id msg) (:symbol msg))
    :order-reject  (format "REJECT order #%d reason=%d" (:order-id msg) (:reason msg))
    :cancel-ack    (format "CANCEL-ACK #%d %s" (:order-id msg) (:symbol msg))
    :cancel-reject (format "CANCEL-REJECT #%d reason=%d" (:order-id msg) (:reason msg))
    :trade         (format "TRADE %s %d @ %.2f (buy:%d/%d sell:%d/%d)"
                          (:symbol msg) (:qty msg) (:price msg)
                          (:buy-user-id msg) (:buy-order-id msg)
                          (:sell-user-id msg) (:sell-order-id msg))
    :book-update   (format "BOOK %s %s %.2f x %d"
                          (:symbol msg) (name (:side msg)) (:price msg) (:qty msg))
    (str "UNKNOWN: " (pr-str msg))))
