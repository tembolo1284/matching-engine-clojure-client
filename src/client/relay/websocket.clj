(ns client.relay.websocket
  "WebSocket server for broadcasting market data to browser clients.
   
   Uses http-kit for async WebSocket handling and optionally
   serves static files for the ClojureScript UI."
  (:require [org.httpkit.server :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private server (atom nil))
(defonce ^:private clients (atom {}))  ; {channel-id {:channel ch :connected-at inst :metadata {}}}

;; =============================================================================
;; JSON Encoding (minimal, no external deps)
;; =============================================================================

(defn- escape-string [^String s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn- to-json
  "Simple JSON encoder for message maps. Handles our specific data types."
  [x]
  (cond
    (nil? x)     "null"
    (boolean? x) (str x)
    (number? x)  (str x)
    (keyword? x) (str "\"" (name x) "\"")
    (string? x)  (str "\"" (escape-string x) "\"")
    (map? x)     (str "{"
                      (->> x
                           (map (fn [[k v]]
                                  (str (to-json (if (keyword? k) (name k) (str k)))
                                       ":"
                                       (to-json v))))
                           (str/join ","))
                      "}")
    (coll? x)    (str "["
                      (->> x (map to-json) (str/join ","))
                      "]")
    :else        (str "\"" (escape-string (str x)) "\"")))

;; =============================================================================
;; Client Management
;; =============================================================================

(defn client-count
  "Number of connected WebSocket clients."
  []
  (count @clients))

(defn client-info
  "Get info about all connected clients."
  []
  (->> @clients
       (map (fn [[id {:keys [connected-at metadata]}]]
              {:id id
               :connected-at (str connected-at)
               :metadata metadata}))
       vec))

(defn- add-client! [ch]
  (let [id (str (UUID/randomUUID))]
    (swap! clients assoc id {:channel ch
                             :connected-at (Instant/now)
                             :metadata {}})
    id))

(defn- remove-client! [ch]
  (swap! clients (fn [m]
                   (->> m
                        (remove (fn [[_ v]] (= (:channel v) ch)))
                        (into {})))))

(defn- get-channels []
  (->> @clients vals (map :channel)))

;; =============================================================================
;; Broadcasting
;; =============================================================================

(defn broadcast!
  "Broadcast a message to all connected WebSocket clients.
   Message is converted to JSON before sending."
  [msg]
  (let [json (to-json msg)
        channels (get-channels)]
    (doseq [ch channels]
      (try
        (http/send! ch json)
        (catch Exception _
          (remove-client! ch))))))

(defn send-to-client!
  "Send a message to a specific client by ID."
  [client-id msg]
  (when-let [client (get @clients client-id)]
    (try
      (http/send! (:channel client) (to-json msg))
      true
      (catch Exception _
        (remove-client! (:channel client))
        false))))

;; =============================================================================
;; Static File Serving
;; =============================================================================

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "ico"  "image/x-icon"
   "woff" "font/woff"
   "woff2" "font/woff2"})

(defn- get-content-type [path]
  (let [ext (last (str/split path #"\."))]
    (get content-types ext "application/octet-stream")))

(defn- serve-static [path]
  (let [path (if (= path "/") "/index.html" path)
        resource-path (str "public" path)
        resource (io/resource resource-path)]
    (if resource
      {:status 200
       :headers {"Content-Type" (get-content-type path)
                 "Cache-Control" "public, max-age=3600"}
       :body (io/input-stream resource)}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))

;; =============================================================================
;; WebSocket Handler
;; =============================================================================

(defn- ws-handler
  "Handle WebSocket connections."
  [on-connect on-message on-disconnect]
  (fn [req]
    (http/with-channel req ch
      (if (http/websocket? ch)
        (let [client-id (add-client! ch)]
          (when on-connect
            (on-connect client-id))
          
          (http/on-receive ch
            (fn [data]
              (when on-message
                (on-message client-id data))))
          
          (http/on-close ch
            (fn [status]
              (remove-client! ch)
              (when on-disconnect
                (on-disconnect client-id status)))))
        
        ;; Not a WebSocket request - return 400
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body "Expected WebSocket connection"}))))

;; =============================================================================
;; HTTP Router
;; =============================================================================

(defn- make-handler
  "Create HTTP handler with WebSocket and optional static file serving."
  [{:keys [serve-static? on-connect on-message on-disconnect]}]
  (let [ws (ws-handler on-connect on-message on-disconnect)]
    (fn [req]
      (let [path (:uri req)
            upgrade? (= "websocket" (str/lower-case (get-in req [:headers "upgrade"] "")))]
        (cond
          ;; WebSocket endpoint
          (or upgrade? (= path "/ws"))
          (ws req)
          
          ;; Health check
          (= path "/health")
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (to-json {:status "ok"
                           :clients (client-count)
                           :uptime-ms (- (System/currentTimeMillis)
                                        (or (:started-at @server) 0))})}
          
          ;; Client list (for debugging)
          (= path "/clients")
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (to-json (client-info))}
          
          ;; Static files
          serve-static?
          (serve-static path)
          
          ;; 404
          :else
          {:status 404
           :headers {"Content-Type" "text/plain"}
           :body "Not Found"})))))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defn start!
  "Start the WebSocket server.
   
   Options:
     :host          - bind address (default: 0.0.0.0)
     :port          - listen port (default: 8080)
     :serve-static? - serve files from resources/public (default: true)
     :on-connect    - fn called with client-id on connect
     :on-message    - fn called with [client-id message] on receive
     :on-disconnect - fn called with [client-id status] on disconnect
   
   Returns the server instance."
  [{:keys [host port serve-static? on-connect on-message on-disconnect]
    :or {host "0.0.0.0"
         port 8080
         serve-static? true}}]
  (when @server
    (throw (ex-info "Server already running" {:port port})))
  
  (let [handler (make-handler {:serve-static? serve-static?
                               :on-connect on-connect
                               :on-message on-message
                               :on-disconnect on-disconnect})
        srv (http/run-server handler {:ip host
                                       :port port
                                       :thread 4
                                       :max-body (* 1024 1024)})]
    (reset! server {:stop-fn srv
                    :started-at (System/currentTimeMillis)
                    :host host
                    :port port})
    (reset! clients {})
    @server))

(defn stop!
  "Stop the WebSocket server."
  []
  (when-let [{:keys [stop-fn]} @server]
    (stop-fn :timeout 1000)
    (reset! server nil)
    (reset! clients {})))

(defn running?
  "Check if server is running."
  []
  (some? @server))

(defn server-info
  "Get server status information."
  []
  (when @server
    (merge @server
           {:clients (client-count)
            :uptime-ms (- (System/currentTimeMillis)
                         (:started-at @server))})))
