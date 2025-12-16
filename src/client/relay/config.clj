(ns client.relay.config
  "Configuration management for the relay server.
   
   Supports CLI args, environment variables, and EDN config files.
   Precedence: CLI > ENV > config file > defaults"
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; =============================================================================
;; Defaults
;; =============================================================================

(def defaults
  {:engine-host    "localhost"
   :engine-port    1234
   :engine-transport :tcp        ; :tcp, :udp, or :multicast
   :multicast-group nil          ; e.g., "239.255.1.1"
   :multicast-iface nil          ; e.g., "eth0"
   :ws-port        8080
   :ws-host        "0.0.0.0"
   :serve-static   true          ; serve UI from resources/public
   :message-filter #{:trade :order-ack :order-reject 
                     :cancel-ack :cancel-reject}
   :log-level      :info})

;; =============================================================================
;; Environment Variables
;; =============================================================================

(defn- env-bool [s]
  (when s
    (#{"true" "1" "yes"} (str/lower-case s))))

(defn- env-int [s]
  (when s
    (try (Integer/parseInt s) (catch Exception _ nil))))

(defn- env-keyword [s]
  (when s
    (keyword (str/lower-case s))))

(defn- env-set [s]
  (when s
    (->> (str/split s #",")
         (map (comp keyword str/trim str/lower-case))
         set)))

(defn from-env
  "Read configuration from environment variables."
  []
  (let [env (System/getenv)]
    (cond-> {}
      (get env "ENGINE_HOST")      (assoc :engine-host (get env "ENGINE_HOST"))
      (get env "ENGINE_PORT")      (assoc :engine-port (env-int (get env "ENGINE_PORT")))
      (get env "ENGINE_TRANSPORT") (assoc :engine-transport (env-keyword (get env "ENGINE_TRANSPORT")))
      (get env "MULTICAST_GROUP")  (assoc :multicast-group (get env "MULTICAST_GROUP"))
      (get env "MULTICAST_IFACE")  (assoc :multicast-iface (get env "MULTICAST_IFACE"))
      (get env "WS_PORT")          (assoc :ws-port (env-int (get env "WS_PORT")))
      (get env "WS_HOST")          (assoc :ws-host (get env "WS_HOST"))
      (get env "SERVE_STATIC")     (assoc :serve-static (env-bool (get env "SERVE_STATIC")))
      (get env "MESSAGE_FILTER")   (assoc :message-filter (env-set (get env "MESSAGE_FILTER")))
      (get env "LOG_LEVEL")        (assoc :log-level (env-keyword (get env "LOG_LEVEL"))))))

;; =============================================================================
;; Config File
;; =============================================================================

(defn from-file
  "Read configuration from EDN file. Returns empty map if file doesn't exist."
  [path]
  (if (.exists (io/file path))
    (-> (slurp path)
        (edn/read-string))
    {}))

;; =============================================================================
;; CLI Parsing
;; =============================================================================

(def cli-spec
  [["-e" "--engine HOST:PORT" "Engine address"
    :parse-fn (fn [s]
                (let [[h p] (str/split s #":")]
                  {:engine-host h
                   :engine-port (Integer/parseInt p)}))]
   ["-t" "--transport TYPE" "Transport: tcp, udp, multicast"
    :parse-fn keyword
    :validate [#{:tcp :udp :multicast} "Must be tcp, udp, or multicast"]]
   ["-m" "--multicast GROUP" "Multicast group address"]
   ["-i" "--interface IFACE" "Network interface for multicast"]
   ["-w" "--ws-port PORT" "WebSocket server port"
    :parse-fn #(Integer/parseInt %)]
   ["-b" "--bind HOST" "WebSocket bind address"]
   ["-c" "--config FILE" "Config file path"]
   ["-f" "--filter TYPES" "Message types to relay (comma-separated)"
    :parse-fn (fn [s]
                (->> (str/split s #",")
                     (map (comp keyword str/trim))
                     set))]
   ["--no-static" "Don't serve static files"]
   ["-v" "--verbose" "Verbose logging"]
   ["-h" "--help" "Show help"]])

(defn- parse-arg
  "Parse a single CLI argument pair."
  [[flag value] args]
  (case flag
    ("-e" "--engine")    [(merge args (let [[h p] (str/split value #":")]
                                        {:engine-host h
                                         :engine-port (Integer/parseInt p)})) 2]
    ("-t" "--transport") [(assoc args :engine-transport (keyword value)) 2]
    ("-m" "--multicast") [(assoc args :multicast-group value) 2]
    ("-i" "--interface") [(assoc args :multicast-iface value) 2]
    ("-w" "--ws-port")   [(assoc args :ws-port (Integer/parseInt value)) 2]
    ("-b" "--bind")      [(assoc args :ws-host value) 2]
    ("-c" "--config")    [(assoc args :config-file value) 2]
    ("-f" "--filter")    [(assoc args :message-filter
                                 (->> (str/split value #",")
                                      (map (comp keyword str/trim))
                                      set)) 2]
    "--no-static"        [(assoc args :serve-static false) 1]
    ("-v" "--verbose")   [(assoc args :log-level :debug) 1]
    ("-h" "--help")      [(assoc args :help true) 1]
    [args 0]))

(defn parse-cli
  "Parse command line arguments into config map."
  [args]
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

(defn load-config
  "Load configuration with precedence: CLI > ENV > file > defaults"
  [cli-args]
  (let [cli (parse-cli cli-args)
        file-config (if-let [f (:config-file cli)]
                      (from-file f)
                      (from-file "relay.edn"))
        env (from-env)]
    (merge defaults file-config env cli)))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate
  "Validate configuration. Returns [ok? errors]."
  [config]
  (let [errors (cond-> []
                 (and (= :multicast (:engine-transport config))
                      (nil? (:multicast-group config)))
                 (conj "Multicast transport requires --multicast GROUP")
                 
                 (not (pos-int? (:engine-port config)))
                 (conj "Invalid engine port")
                 
                 (not (pos-int? (:ws-port config)))
                 (conj "Invalid WebSocket port")
                 
                 (empty? (:message-filter config))
                 (conj "Message filter cannot be empty"))]
    [(empty? errors) errors]))

;; =============================================================================
;; Help
;; =============================================================================

(def help-text
  "Matching Engine Relay Server

USAGE:
  clojure -M:relay [options]
  java -jar relay.jar [options]

OPTIONS:
  -e, --engine HOST:PORT    Engine address (default: localhost:1234)
  -t, --transport TYPE      Transport type: tcp, udp, multicast (default: tcp)
  -m, --multicast GROUP     Multicast group address (required for multicast)
  -i, --interface IFACE     Network interface for multicast
  -w, --ws-port PORT        WebSocket server port (default: 8080)
  -b, --bind HOST           WebSocket bind address (default: 0.0.0.0)
  -c, --config FILE         Config file path (default: relay.edn)
  -f, --filter TYPES        Message types to relay (default: trade,order-ack,...)
      --no-static           Don't serve static files
  -v, --verbose             Enable debug logging
  -h, --help                Show this help

ENVIRONMENT VARIABLES:
  ENGINE_HOST, ENGINE_PORT, ENGINE_TRANSPORT
  MULTICAST_GROUP, MULTICAST_IFACE
  WS_PORT, WS_HOST, MESSAGE_FILTER, LOG_LEVEL

EXAMPLES:
  # Connect to local engine via TCP
  clojure -M:relay -e localhost:1234

  # Subscribe to multicast market data
  clojure -M:relay -t multicast -m 239.255.1.1 -w 9000

  # Use config file
  clojure -M:relay -c production.edn")

(defn print-help []
  (println help-text))
