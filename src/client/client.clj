(ns client.client
  "TCP and UDP client with protocol auto-detection for matching engine."
  (:require [client.protocol :as proto])
  (:import [java.net Socket InetSocketAddress DatagramSocket DatagramPacket
            InetAddress SocketTimeoutException]
           [java.io DataInputStream DataOutputStream BufferedInputStream]
           [java.nio ByteBuffer ByteOrder]))

;; =============================================================================
;; Protocol Types
;; =============================================================================

(def protocols #{:binary :csv})

;; =============================================================================
;; TCP Connection Management
;; =============================================================================

(defn tcp-connect
  "Connect to matching engine server via TCP.
   
   Args:
     host: Server hostname (default \"localhost\")
     port: Server port (default 1234)
     opts: Optional map with :timeout-ms (default 5000)
   
   Returns: Connection map with :type :tcp and socket info"
  ([] (tcp-connect "localhost" 1234))
  ([host port] (tcp-connect host port {}))
  ([host port opts]
   (let [timeout (get opts :timeout-ms 5000)
         socket (Socket.)]
     (.connect socket (InetSocketAddress. ^String host ^int port) timeout)
     (.setTcpNoDelay socket true)
     (.setSoTimeout socket timeout)
     {:type :tcp
      :socket socket
      :in (DataInputStream. (BufferedInputStream. (.getInputStream socket)))
      :out (DataOutputStream. (.getOutputStream socket))
      :host host
      :port port
      :protocol (atom nil)})))

(defn tcp-disconnect
  "Close TCP connection."
  [conn]
  (when-let [socket (:socket conn)]
    (try
      (.close ^Socket socket)
      (catch Exception _))))

(defn tcp-connected?
  "Check if TCP connection is open."
  [conn]
  (when-let [socket (:socket conn)]
    (and (not (.isClosed ^Socket socket))
         (.isConnected ^Socket socket))))

;; =============================================================================
;; UDP Connection Management
;; =============================================================================

(defn udp-connect
  "Create UDP client with bidirectional communication.
   
   Args:
     host: Server hostname
     port: Server port
     opts: Optional map with :timeout-ms, :local-port
   
   Returns: Connection map with :type :udp"
  ([] (udp-connect "localhost" 1234))
  ([host port] (udp-connect host port {}))
  ([host port opts]
   (let [timeout (get opts :timeout-ms 5000)
         local-port (get opts :local-port 0)  ; 0 = ephemeral port
         socket (DatagramSocket. local-port)
         server-addr (InetAddress/getByName host)]
     (.setSoTimeout socket timeout)
     {:type :udp
      :socket socket
      :server-addr server-addr
      :server-port port
      :host host
      :port port
      :recv-buf (byte-array 65536)
      :protocol (atom nil)})))

(defn udp-disconnect
  "Close UDP socket."
  [conn]
  (when-let [socket (:socket conn)]
    (try
      (.close ^DatagramSocket socket)
      (catch Exception _))))

(defn udp-connected?
  "Check if UDP socket is open."
  [conn]
  (when-let [socket (:socket conn)]
    (not (.isClosed ^DatagramSocket socket))))

;; =============================================================================
;; Generic Connection API
;; =============================================================================

(defn connect
  "Connect to matching engine (TCP by default).
   
   Args:
     host: Server hostname
     port: Server port
     opts: Map with :transport (:tcp or :udp), :timeout-ms, etc.
   
   Returns: Connection map"
  ([] (connect "localhost" 1234))
  ([host port] (connect host port {}))
  ([host port opts]
   (case (get opts :transport :tcp)
     :tcp (tcp-connect host port opts)
     :udp (udp-connect host port opts))))

(defn disconnect
  "Close connection."
  [conn]
  (case (:type conn)
    :tcp (tcp-disconnect conn)
    :udp (udp-disconnect conn)
    nil))

(defn connected?
  "Check if connected."
  [conn]
  (case (:type conn)
    :tcp (tcp-connected? conn)
    :udp (udp-connected? conn)
    false))

(defn get-protocol
  "Get detected protocol (:binary or :csv)."
  [conn]
  @(:protocol conn))

(defn set-protocol!
  "Manually set protocol."
  [conn proto]
  (reset! (:protocol conn) proto))

;; =============================================================================
;; TCP Framing
;; =============================================================================

(defn- tcp-write-frame
  "Write a message with 4-byte big-endian length prefix."
  [^DataOutputStream out ^bytes data]
  (.writeInt out (alength data))
  (.write out data)
  (.flush out))

(defn- tcp-read-frame
  "Read a length-prefixed frame. Returns byte array or nil on timeout/EOF."
  [^DataInputStream in]
  (try
    (let [len (.readInt in)]
      (when (and (pos? len) (< len 65536))
        (let [arr (byte-array len)]
          (.readFully in arr)
          arr)))
    (catch java.io.EOFException _ nil)
    (catch SocketTimeoutException _ nil)))

(defn- tcp-try-read-frame
  "Non-blocking read attempt. Returns byte array or nil."
  [conn timeout-ms]
  (let [socket ^Socket (:socket conn)
        orig-timeout (.getSoTimeout socket)]
    (try
      (.setSoTimeout socket (int timeout-ms))
      (tcp-read-frame (:in conn))
      (catch SocketTimeoutException _ nil)
      (finally
        (.setSoTimeout socket orig-timeout)))))

;; =============================================================================
;; UDP Send/Receive
;; =============================================================================

(defn- udp-send
  "Send data via UDP."
  [conn ^bytes data]
  (let [socket ^DatagramSocket (:socket conn)
        packet (DatagramPacket. data (alength data)
                                ^InetAddress (:server-addr conn)
                                ^int (:server-port conn))]
    (.send socket packet)))

(defn- udp-recv
  "Receive data via UDP. Returns byte array or nil on timeout."
  [conn]
  (try
    (let [socket ^DatagramSocket (:socket conn)
          buf ^bytes (:recv-buf conn)
          packet (DatagramPacket. buf (alength buf))]
      (.receive socket packet)
      (let [len (.getLength packet)
            result (byte-array len)]
        (System/arraycopy buf 0 result 0 len)
        result))
    (catch SocketTimeoutException _ nil)))

(defn- udp-try-recv
  "Non-blocking UDP receive. Returns byte array or nil."
  [conn timeout-ms]
  (let [socket ^DatagramSocket (:socket conn)
        orig-timeout (.getSoTimeout socket)]
    (try
      (.setSoTimeout socket (int timeout-ms))
      (udp-recv conn)
      (catch SocketTimeoutException _ nil)
      (finally
        (.setSoTimeout socket orig-timeout)))))

;; =============================================================================
;; Protocol-Aware Send
;; =============================================================================

(defn- buf->bytes
  "Convert ByteBuffer to byte array."
  [^ByteBuffer buf]
  (let [arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

(defn send-raw!
  "Send raw bytes (for protocol detection probes)."
  [conn ^bytes data]
  (case (:type conn)
    :tcp (tcp-write-frame (:out conn) data)
    :udp (udp-send conn data)))

(defn send-message!
  "Send a message using detected protocol."
  [conn msg-type & args]
  (let [proto (or @(:protocol conn) :binary)
        data (case msg-type
               :new-order
               (let [[user-id symbol price qty side order-id] args]
                 (if (= proto :binary)
                   (buf->bytes (proto/encode-new-order user-id symbol price qty side order-id))
                   (proto/csv-encode-new-order user-id symbol price qty side order-id)))
               
               :cancel
               (let [[user-id symbol order-id] args]
                 (if (= proto :binary)
                   (buf->bytes (proto/encode-cancel user-id symbol order-id))
                   (proto/csv-encode-cancel user-id symbol order-id)))
               
               :flush
               (if (= proto :binary)
                 (buf->bytes (proto/encode-flush))
                 (proto/csv-encode-flush)))]
    (send-raw! conn data)))

;; =============================================================================
;; Receive
;; =============================================================================

(defn recv-raw
  "Receive raw bytes from server."
  ([conn] (recv-raw conn nil))
  ([conn timeout-ms]
   (case (:type conn)
     :tcp (if timeout-ms
            (tcp-try-read-frame conn timeout-ms)
            (tcp-read-frame (:in conn)))
     :udp (if timeout-ms
            (udp-try-recv conn timeout-ms)
            (udp-recv conn)))))

(defn recv-message
  "Receive and decode a message."
  ([conn] (recv-message conn nil))
  ([conn timeout-ms]
   (when-let [data (recv-raw conn timeout-ms)]
     (try
       (proto/decode-auto data)
       (catch Exception e
         {:type :parse-error :error (.getMessage e) :data data})))))

(defn recv-all
  "Receive all available messages with short timeout.
   Returns vector of decoded messages."
  ([conn] (recv-all conn 100))
  ([conn timeout-ms]
   (loop [msgs []]
     (if-let [msg (recv-message conn timeout-ms)]
       (recur (conj msgs msg))
       msgs))))

;; =============================================================================
;; Protocol Detection
;; =============================================================================

(defn detect-protocol!
  "Auto-detect server protocol by sending probe messages.
   
   Strategy:
   1. Send binary probe order
   2. If binary response → :binary
   3. Otherwise → try CSV, or default to :binary
   
   Returns: :binary or :csv"
  [conn]
  (let [probe-user 999999
        probe-symbol "PROBE"
        probe-order 999999
        detected (atom nil)]
    
    ;; Try binary first
    (try
      (send-raw! conn (buf->bytes (proto/encode-new-order 
                                   probe-user probe-symbol 1 1 :buy probe-order)))
      (Thread/sleep 100)
      (when-let [response (recv-raw conn 500)]
        (if (proto/binary-message? response)
          (do
            ;; Cancel probe
            (send-raw! conn (buf->bytes (proto/encode-cancel 
                                         probe-user probe-symbol probe-order)))
            (Thread/sleep 50)
            (recv-all conn 100)
            (reset! detected :binary))
          ;; Got non-binary response - might be CSV
          (do
            (recv-all conn 100)
            (reset! detected :csv))))
      (catch Exception _))
    
    (when-not @detected
      ;; Try CSV
      (try
        (send-raw! conn (proto/csv-encode-new-order 
                         probe-user probe-symbol 1 1 :buy (inc probe-order)))
        (Thread/sleep 100)
        (when-let [response (recv-raw conn 500)]
          (if (proto/binary-message? response)
            (reset! detected :binary)
            (do
              (send-raw! conn (proto/csv-encode-cancel 
                               probe-user probe-symbol (inc probe-order)))
              (Thread/sleep 50)
              (recv-all conn 100)
              (reset! detected :csv))))
        (catch Exception _)))
    
    (let [proto (or @detected :binary)]
      (reset! (:protocol conn) proto)
      proto)))

;; =============================================================================
;; High-Level Order Operations
;; =============================================================================

(defn send-order!
  "Send a new order."
  [conn user-id symbol price qty side order-id]
  (send-message! conn :new-order user-id symbol price qty side order-id))

(defn send-cancel!
  "Send a cancel order request."
  [conn user-id symbol order-id]
  (send-message! conn :cancel user-id symbol order-id))

(defn send-flush!
  "Send a flush command."
  [conn]
  (send-message! conn :flush))

;; =============================================================================
;; Connection Macro
;; =============================================================================

(defmacro with-connection
  "Execute body with a connection, ensuring cleanup.
   
   Usage:
     (with-connection [c \"localhost\" 1234]
       (send-order! c 1 \"IBM\" 10000 100 :buy 1))
     
     (with-connection [c \"localhost\" 1234 {:transport :udp}]
       ...)"
  [[binding host port & [opts]] & body]
  `(let [~binding (connect ~host ~port ~(or opts {}))]
     (try
       ~@body
       (finally
         (disconnect ~binding)))))
