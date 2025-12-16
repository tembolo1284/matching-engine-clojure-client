(ns position-manager.db
  "Application state schema and initial values.")

(def default-db
  {:connection {:status :disconnected  ; :disconnected, :connecting, :connected
                :url nil
                :reconnect-attempts 0}
   
   ;; Orders indexed by "userId-orderId"
   :orders {}
   
   ;; Recent trades (newest first)
   :trades []
   
   ;; Message log entries
   :log []
   
   ;; UI state
   :ui {:selected-tab :orders      ; :orders, :trades, :log
        :filter-symbol nil         ; nil = show all
        :theme :dark}
   
   ;; Statistics
   :stats {:messages-received 0
           :trades-count 0
           :orders-acked 0
           :orders-rejected 0
           :connected-at nil}})
