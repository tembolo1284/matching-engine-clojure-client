(ns client.relay.config
  "Configuration management for the relay server."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; =============================================================================
;; Defaults
;; =============================================================================

(def defaults
  {:engine-host    "localhost"
   :engine-port    1234
   :engine-transport :tcp
   :multicast-group nil
   :multicast-port  1236
   :multicast-iface nil
   :ws-port        8080
   :ws-host        "0.0.0.0"
   :serve-static   true
   :message-filter #{:trade :order-ack :order-reject 
                     :cancel-ack :cancel-reject :top-of-book}
   :log-level      :info})

;; =============================================================================
;; Environment Variables
;; =============================================================================

(defn- env-bool [s]
  (when s (#{"true" "1" "yes"} (str/lower-case s))))

(defn- env-int [s]
  (when s (try (Integer/parseInt s) (catch Exception _ nil))))

(defn- env-keyword [s]
  (when s (keyword (str/lower-case s))))

(defn- env-set [s]
  (when s
    (->> (str/split s #",")
         (map (comp keyword str/trim str/lower-case))
         set)))

(defn from-env []
  (let [env (System/getenv)]
    (cond-> {}
      (get env "ENGINE_HOST")      (assoc :engine-host (get env "ENGINE_HOST"))
      (get env "ENGINE_PORT")      (assoc :engine-port (env-int (get env "ENGINE_PORT")))
      (get env "ENGINE_TRANSPORT") (assoc :engine-transport (env-keyword (get env "ENGINE_TRANSPORT")))
      (get env "MULTICAST_GROUP")  (assoc :multicast-group (get env "MULTICAST_GROUP")
                                          :engine-transport :multicast)
      (get env "MULTICAST_IFACE")  (assoc :multicast-iface (get env "MULTICAST_IFACE"))
      (get env "WS_PORT")          (assoc :ws-port (env-int (get env "WS_PORT")))
      (get env "WS_HOST")          (assoc :ws-host (get env "WS_HOST"))
      (get env "SERVE_STATIC")     (assoc :serve-static (env-bool (get env "SERVE_STATIC")))
      (get env "MESSAGE_FILTER")   (assoc :message-filter (env-set (get env "MESSAGE_FILTER")))
      (get env "LOG_LEVEL")        (assoc :log-level (env-keyword (get env "LOG_LEVEL"))))))

;; =============================================================================
;; Config File
;; =============================================================================

(defn from-file [path]
  (if (.exists (io/file path))
    (-> (slurp path) edn/read-string)
    {}))

;; =============================================================================
;; CLI Parsing
;; =============================================================================

(defn- parse-arg [[flag value] args]
  (case flag
    ("-e" "--engine")
    (let [[h p] (str/split value #":")]
      [(assoc args :engine-host h :engine-port (Integer/parseInt p)) 2])
    
    ("-t" "--transport")
    [(assoc args :engine-transport (keyword value)) 2]
    
    ;; -m sets BOTH multicast-group AND transport to :multicast
    ("-m" "--multicast")
    (let [[group port] (str/split value #":")]
      [(assoc args 
              :multicast-group group
              :multicast-port (if port (Integer/parseInt port) 1236)
              :engine-transport :multicast) 2])
    
    ("-i" "--interface")
    [(assoc args :multicast-iface value) 2]
    
    ("-w" "--ws-port")
    [(assoc args :ws-port (Integer/parseInt value)) 2]
    
    ("-b" "--bind")
    [(assoc args :ws-host value) 2]
    
    ("-c" "--config")
    [(assoc args :config-file value) 2]
    
    ("-f" "--filter")
    [(assoc args :message-filter
            (->> (str/split value #",")
                 (map (comp keyword str/trim))
                 set)) 2]
    
    "--no-static"
    [(assoc args :serve-static false) 1]
    
    ("--all")
    [(assoc args :message-filter #{:trade :order-ack :order-reject 
                                   :cancel-ack :cancel-reject :top-of-book}) 1]
    
    ("-v" "--verbose")
    [(assoc args :log-level :debug) 1]
    
    ("-h" "--help")
    [(assoc args :help true) 1]
    
    ;; Unknown flag
    [args 0]))

(defn parse-cli [args]
  (loop [args (vec args)
         config {}]
    (if (empty? args)
      config
      (let [[new-config consumed] (parse-arg args config)]
        (if (pos? consumed)
          (recur (subvec args consumed) new-config)
          (recur (subvec args 1) config))))))

;; =============================================================================
;; Merged Config
;; =============================================================================

(defn load-config [cli-args]
  (let [cli (parse-cli cli-args)
        file-config (if-let [f (:config-file cli)]
                      (from-file f)
                      (from-file "relay.edn"))
        env (from-env)]
    (merge defaults file-config env cli)))

;; =============================================================================
;; Validation & Help
;; =============================================================================

(defn validate [config]
  (let [errors (cond-> []
                 (and (= :multicast (:engine-transport config))
                      (nil? (:multicast-group config)))
                 (conj "Multicast transport requires -m GROUP:PORT")
                 
                 (not (pos-int? (:engine-port config)))
                 (conj "Invalid engine port")
                 
                 (not (pos-int? (:ws-port config)))
                 (conj "Invalid WebSocket port"))]
    [(empty? errors) errors]))

(def help-text
  "Matching Engine Relay Server

USAGE:
  clojure -M:relay [options]

OPTIONS:
  -e, --engine HOST:PORT    Engine TCP address (default: localhost:1234)
  -m, --multicast GROUP:PORT  Multicast group (e.g., 239.255.1.1:1236)
  -t, --transport TYPE      Transport: tcp, udp, multicast (default: tcp)
  -i, --interface IFACE     Network interface for multicast
  -w, --ws-port PORT        WebSocket server port (default: 8080)
  -b, --bind HOST           WebSocket bind address (default: 0.0.0.0)
  -f, --filter TYPES        Message types to relay (comma-separated)
      --all                 Relay all message types including TOB
      --no-static           Don't serve static files
  -v, --verbose             Enable debug logging
  -h, --help                Show this help

EXAMPLES:
  # TCP connection to engine
  clojure -M:relay -e localhost:1234 -w 8080

  # Multicast market data feed
  clojure -M:relay -m 239.255.1.1:1236 -w 8080

  # Both (TCP for orders, multicast for data)
  clojure -M:relay -m 239.255.1.1:1236 -w 8080")

(defn print-help []
  (println help-text))
