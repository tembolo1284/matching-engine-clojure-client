(ns position-manager.subs
  "Re-frame subscriptions."
  (:require [re-frame.core :as rf]))

;; =============================================================================
;; Connection
;; =============================================================================

(rf/reg-sub
 :connection/status
 (fn [db _]
   (get-in db [:connection :status])))

(rf/reg-sub
 :connection/connected?
 :<- [:connection/status]
 (fn [status _]
   (= status :connected)))

(rf/reg-sub
 :connection/info
 (fn [db _]
   (:connection db)))

;; =============================================================================
;; Orders
;; =============================================================================

(rf/reg-sub
 :orders/all
 (fn [db _]
   (vals (:orders db))))

(rf/reg-sub
 :orders/by-symbol
 :<- [:orders/all]
 (fn [orders [_ symbol]]
   (if symbol
     (filter #(= (:symbol %) symbol) orders)
     orders)))

(rf/reg-sub
 :orders/count
 :<- [:orders/all]
 (fn [orders _]
   (count orders)))

(rf/reg-sub
 :orders/symbols
 :<- [:orders/all]
 (fn [orders _]
   (->> orders
        (map :symbol)
        distinct
        sort)))

;; =============================================================================
;; Trades
;; =============================================================================

(rf/reg-sub
 :trades/all
 (fn [db _]
   (:trades db)))

(rf/reg-sub
 :trades/by-symbol
 :<- [:trades/all]
 (fn [trades [_ symbol]]
   (if symbol
     (filter #(= (:symbol %) symbol) trades)
     trades)))

(rf/reg-sub
 :trades/count
 :<- [:trades/all]
 (fn [trades _]
   (count trades)))

(rf/reg-sub
 :trades/recent
 :<- [:trades/all]
 (fn [trades [_ n]]
   (take (or n 10) trades)))

(rf/reg-sub
 :trades/symbols
 :<- [:trades/all]
 (fn [trades _]
   (->> trades
        (map :symbol)
        distinct
        sort)))

;; =============================================================================
;; Log
;; =============================================================================

(rf/reg-sub
 :log/entries
 (fn [db _]
   (:log db)))

(rf/reg-sub
 :log/recent
 :<- [:log/entries]
 (fn [entries [_ n]]
   (take (or n 50) entries)))

;; =============================================================================
;; Stats
;; =============================================================================

(rf/reg-sub
 :stats/all
 (fn [db _]
   (:stats db)))

(rf/reg-sub
 :stats/summary
 :<- [:stats/all]
 :<- [:orders/count]
 :<- [:trades/count]
 (fn [[stats orders-count trades-count] _]
   {:messages (:messages-received stats)
    :active-orders orders-count
    :trades trades-count
    :acked (:orders-acked stats)
    :rejected (:orders-rejected stats)
    :connected-at (:connected-at stats)}))

;; =============================================================================
;; UI
;; =============================================================================

(rf/reg-sub
 :ui/selected-tab
 (fn [db _]
   (get-in db [:ui :selected-tab])))

(rf/reg-sub
 :ui/filter-symbol
 (fn [db _]
   (get-in db [:ui :filter-symbol])))

(rf/reg-sub
 :ui/theme
 (fn [db _]
   (get-in db [:ui :theme])))

;; =============================================================================
;; Filtered Views
;; =============================================================================

(rf/reg-sub
 :view/orders
 :<- [:orders/all]
 :<- [:ui/filter-symbol]
 (fn [[orders filter-symbol] _]
   (cond->> orders
     filter-symbol (filter #(= (:symbol %) filter-symbol))
     true (sort-by :acked-at)
     true reverse)))

(rf/reg-sub
 :view/trades
 :<- [:trades/all]
 :<- [:ui/filter-symbol]
 (fn [[trades filter-symbol] _]
   (if filter-symbol
     (filter #(= (:symbol %) filter-symbol) trades)
     trades)))

(rf/reg-sub
 :view/all-symbols
 :<- [:orders/symbols]
 :<- [:trades/symbols]
 (fn [[order-symbols trade-symbols] _]
   (->> (concat order-symbols trade-symbols)
        distinct
        sort)))
