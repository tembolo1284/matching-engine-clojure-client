(ns client.protocol
  "Binary protocol encoder/decoder for matching engine communication.
   
   Wire format (little-endian):
   ┌──────────┬──────────┬─────────────────┐
   │ Magic    │ Length   │ Payload         │
   │ 2 bytes  │ 2 bytes  │ N bytes         │
   │ 0x4D45   │ uint16   │ (type+fields)   │
   └──────────┴──────────┴─────────────────┘
   
   Shared by REPL client, relay, and any JVM consumer."
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Constants
;; =============================================================================

(def magic-bytes (byte-array [(unchecked-byte 0x4D) (unchecked-byte 0x45)]))

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

(defn encode-flush
  "Encode Flush message.
   Fields: type(1) = 1 byte"
  []
  (-> (make-buffer 5)
      (put-header!)
      (.put (byte (message-types :flush)))
      (finalize-length!)
      (.array)))

(defn encode-message
  "Encode any outbound message by type."
  [msg]
  (case (:type msg)
    :new-order (encode-new-order msg)
    :cancel    (encode-cancel msg)
    :flush     (encode-flush)
    (throw (ex-info "Unknown message type" {:msg msg}))))

;; =============================================================================
;; Decoders
;; =============================================================================

(defn- decode-order-ack
  [^ByteBuffer buf]
  {:type     :order-ack
   :user-id  (.getInt buf)
   :order-id (.getInt buf)
   :symbol   (let [bs (byte-array 8)] (.get buf bs) (decode-symbol bs))})

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
