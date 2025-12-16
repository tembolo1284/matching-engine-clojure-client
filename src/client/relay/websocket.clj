(ns client.relay.websocket
  "WebSocket server for browser clients."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.net ServerSocket Socket]
           [java.io BufferedReader InputStreamReader OutputStream BufferedInputStream]
           [java.security MessageDigest]
           [java.util Base64]
           [java.util.concurrent ConcurrentHashMap]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private clients (ConcurrentHashMap.))
(defonce ^:private server-socket (atom nil))
(defonce ^:private running (atom false))

;; =============================================================================
;; WebSocket Handshake
;; =============================================================================

(def ^:private ws-magic "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- compute-accept-key [key]
  (let [digest (MessageDigest/getInstance "SHA-1")
        hash (.digest digest (.getBytes (str key ws-magic)))]
    (.encodeToString (Base64/getEncoder) hash)))

(defn- parse-http-request [^BufferedReader reader]
  (let [request-line (.readLine reader)]
    (when request-line
      (loop [headers {}]
        (let [line (.readLine reader)]
          (if (or (nil? line) (empty? line))
            {:request-line request-line :headers headers}
            (let [[k v] (clojure.string/split line #": " 2)]
              (recur (assoc headers (clojure.string/lower-case k) v)))))))))

(defn- send-handshake-response [^OutputStream out accept-key]
  (let [response (str "HTTP/1.1 101 Switching Protocols\r\n"
                      "Upgrade: websocket\r\n"
                      "Connection: Upgrade\r\n"
                      "Sec-WebSocket-Accept: " accept-key "\r\n"
                      "\r\n")]
    (.write out (.getBytes response))
    (.flush out)))

;; =============================================================================
;; WebSocket Framing
;; =============================================================================

(defn- send-ws-frame [^OutputStream out ^String text]
  (let [payload (.getBytes text "UTF-8")
        len (alength payload)]
    ;; Text frame opcode
    (.write out 0x81)
    ;; Length
    (cond
      (< len 126)
      (.write out len)
      
      (< len 65536)
      (do
        (.write out 126)
        (.write out (bit-shift-right len 8))
        (.write out (bit-and len 0xFF)))
      
      :else
      (do
        (.write out 127)
        (doseq [i (range 7 -1 -1)]
          (.write out (bit-and (bit-shift-right len (* i 8)) 0xFF)))))
    
    (.write out payload)
    (.flush out)))

(defn- read-ws-frame [^BufferedInputStream in]
  (let [b1 (.read in)]
    (when (not= b1 -1)
      (let [_fin (bit-and b1 0x80)
            opcode (bit-and b1 0x0F)
            b2 (.read in)
            masked (pos? (bit-and b2 0x80))
            len1 (bit-and b2 0x7F)
            len (cond
                  (< len1 126) len1
                  (= len1 126) (+ (bit-shift-left (.read in) 8) (.read in))
                  :else (reduce (fn [acc _] (+ (bit-shift-left acc 8) (.read in))) 0 (range 8)))
            mask (when masked
                   (byte-array [(.read in) (.read in) (.read in) (.read in)]))
            payload (byte-array len)]
        (when (pos? len)
          (.read in payload))
        (when masked
          (dotimes [i len]
            (aset-byte payload i (byte (bit-xor (aget payload i) (aget ^bytes mask (mod i 4)))))))
        {:opcode opcode
         :payload (String. payload "UTF-8")}))))

;; =============================================================================
;; Static File Serving
;; =============================================================================

(defn- content-type [path]
  (cond
    (clojure.string/ends-with? path ".html") "text/html"
    (clojure.string/ends-with? path ".css") "text/css"
    (clojure.string/ends-with? path ".js") "application/javascript"
    (clojure.string/ends-with? path ".json") "application/json"
    (clojure.string/ends-with? path ".png") "image/png"
    (clojure.string/ends-with? path ".ico") "image/x-icon"
    :else "text/plain"))

(defn- serve-static [^OutputStream out path]
  (let [resource-path (if (= path "/") "/index.html" path)
        resource (io/resource (str "public" resource-path))]
    (if resource
      (let [content (slurp resource)
            response (str "HTTP/1.1 200 OK\r\n"
                          "Content-Type: " (content-type resource-path) "\r\n"
                          "Content-Length: " (count (.getBytes content "UTF-8")) "\r\n"
                          "\r\n"
                          content)]
        (.write out (.getBytes response "UTF-8")))
      (let [response "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found"]
        (.write out (.getBytes response))))
    (.flush out)))

;; =============================================================================
;; Client Handling
;; =============================================================================

(defn- handle-client [^Socket socket]
  (let [client-id (str (java.util.UUID/randomUUID))
        in (BufferedInputStream. (.getInputStream socket))
        out (.getOutputStream socket)
        reader (BufferedReader. (InputStreamReader. in))]
    (try
      (when-let [{:keys [request-line headers]} (parse-http-request reader)]
        (let [[_ path] (re-find #"GET ([^ ]+)" request-line)]
          (if (= path "/ws")
            ;; WebSocket upgrade
            (when-let [ws-key (get headers "sec-websocket-key")]
              (let [accept-key (compute-accept-key ws-key)]
                (send-handshake-response out accept-key)
                (.put clients client-id {:socket socket :out out})
                (println (format "[info] Client connected: %s (total: %d)" 
                                 client-id (.size clients)))
                
                ;; Read loop
                (loop []
                  (when-let [{:keys [opcode payload]} (read-ws-frame in)]
                    (case opcode
                      8 nil  ; Close
                      9 (do  ; Ping -> Pong
                          (send-ws-frame out payload)
                          (recur))
                      10 (recur)  ; Pong - ignore
                      (recur))))))
            
            ;; Regular HTTP - serve static
            (serve-static out path))))
      
      (catch Exception e
        (when-not (or (instance? java.net.SocketException e)
                      (instance? java.io.EOFException e))
          (println "[warn] Client error:" (.getMessage e))))
      
      (finally
        (.remove clients client-id)
        (try (.close socket) (catch Exception _))
        (println (format "[info] Client disconnected: %s (total: %d)" 
                         client-id (.size clients)))))))

;; =============================================================================
;; Broadcast
;; =============================================================================

(defn broadcast!
  "Send message to all connected WebSocket clients."
  [msg]
  (let [json-msg (if (string? msg) msg (json/write-str msg))]
    (doseq [[client-id {:keys [out]}] clients]
      (try
        (send-ws-frame out json-msg)
        (catch Exception e
          (println (format "[warn] Failed to send to %s: %s" client-id (.getMessage e)))
          (.remove clients client-id))))))

;; =============================================================================
;; Server
;; =============================================================================

(defn start! [host port]
  (reset! running true)
  (let [ss (ServerSocket. port 50 (java.net.InetAddress/getByName host))]
    (reset! server-socket ss)
    (println (format "[info] WebSocket server started on %s:%d" host port))
    
    (future
      (while @running
        (try
          (let [client (.accept ss)]
            (future (handle-client client)))
          (catch java.net.SocketException _
            ;; Server closed
            )
          (catch Exception e
            (when @running
              (println "[error] Accept error:" (.getMessage e)))))))))

(defn stop! []
  (reset! running false)
  (when-let [ss @server-socket]
    (try (.close ss) (catch Exception _)))
  (doseq [[_ {:keys [socket]}] clients]
    (try (.close ^Socket socket) (catch Exception _)))
  (.clear clients)
  (println "[info] WebSocket server stopped"))
