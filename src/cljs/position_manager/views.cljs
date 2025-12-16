(ns position-manager.views
  "Reagent view components."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- format-time [date]
  (when date
    (.toLocaleTimeString date)))

(defn- format-price [price]
  (when price
    (.toFixed price 2)))

(defn- format-number [n]
  (when n
    (.toLocaleString n)))

;; =============================================================================
;; Status Indicator
;; =============================================================================

(defn status-indicator []
  (let [status @(rf/subscribe [:connection/status])]
    [:div.status
     [:div.status-dot
      {:class (case status
                :connected "connected"
                :connecting "connecting"
                "disconnected")}]
     [:span.status-text
      (case status
        :connected "Connected"
        :connecting "Connecting..."
        "Disconnected")]]))

;; =============================================================================
;; Stats Bar
;; =============================================================================

(defn stats-bar []
  (let [stats @(rf/subscribe [:stats/summary])]
    [:div.stats-bar
     [:div.stat
      [:span.stat-value (format-number (:messages stats))]
      [:span.stat-label "Messages"]]
     [:div.stat
      [:span.stat-value (:active-orders stats)]
      [:span.stat-label "Active Orders"]]
     [:div.stat
      [:span.stat-value (:trades stats)]
      [:span.stat-label "Trades"]]
     [:div.stat
      [:span.stat-value (:acked stats)]
      [:span.stat-label "Acked"]]
     [:div.stat
      [:span.stat-value (:rejected stats)]
      [:span.stat-label "Rejected"]]]))

;; =============================================================================
;; Symbol Filter
;; =============================================================================

(defn symbol-filter []
  (let [symbols @(rf/subscribe [:view/all-symbols])
        current @(rf/subscribe [:ui/filter-symbol])]
    [:div.symbol-filter
     [:select
      {:value (or current "")
       :on-change #(rf/dispatch [:ui/set-filter
                                 (let [v (-> % .-target .-value)]
                                   (when (seq v) v))])}
      [:option {:value ""} "All Symbols"]
      (for [sym symbols]
        ^{:key sym}
        [:option {:value sym} sym])]]))

;; =============================================================================
;; Orders Table
;; =============================================================================

(defn orders-table []
  (let [orders @(rf/subscribe [:view/orders])]
    [:div.panel
     [:div.panel-header
      [:h2 "Active Orders"]
      [:span.badge (count orders)]]
     [:div.table-container
      (if (empty? orders)
        [:div.empty-state "No active orders"]
        [:table.data-table
         [:thead
          [:tr
           [:th "Order ID"]
           [:th "User"]
           [:th "Symbol"]
           [:th "Status"]
           [:th "Time"]]]
         [:tbody
          (for [order orders]
            ^{:key (:key order)}
            [:tr
             [:td (:order-id order)]
             [:td (:user-id order)]
             [:td.symbol (:symbol order)]
             [:td [:span.status-badge {:class (name (:status order))}
                   (name (:status order))]]
             [:td.time (format-time (:acked-at order))]])]])]]))

;; =============================================================================
;; Trades Table
;; =============================================================================

(defn trades-table []
  (let [trades @(rf/subscribe [:view/trades])]
    [:div.panel
     [:div.panel-header
      [:h2 "Recent Trades"]
      [:span.badge (count trades)]]
     [:div.table-container
      (if (empty? trades)
        [:div.empty-state "No trades yet"]
        [:table.data-table
         [:thead
          [:tr
           [:th "Symbol"]
           [:th "Qty"]
           [:th "Price"]
           [:th "Buy"]
           [:th "Sell"]
           [:th "Time"]]]
         [:tbody
          (for [[idx trade] (map-indexed vector trades)]
            ^{:key idx}
            [:tr.trade-row
             [:td.symbol (:symbol trade)]
             [:td.qty (format-number (:qty trade))]
             [:td.price (format-price (:price trade))]
             [:td (str (:buy-user-id trade) "/" (:buy-order-id trade))]
             [:td (str (:sell-user-id trade) "/" (:sell-order-id trade))]
             [:td.time (format-time (:executed-at trade))]])]])]]))

;; =============================================================================
;; Message Log
;; =============================================================================

(defn log-entry-class [level]
  (case level
    :error "log-error"
    :warn "log-warn"
    :success "log-success"
    :trade "log-trade"
    :info "log-info"
    ""))

(defn message-log []
  (let [entries @(rf/subscribe [:log/recent 100])]
    [:div.panel.log-panel
     [:div.panel-header
      [:h2 "Message Log"]
      [:button.btn-small
       {:on-click #(rf/dispatch [:log/clear])}
       "Clear"]]
     [:div.log-container
      (if (empty? entries)
        [:div.empty-state "No log entries"]
        [:div.log-entries
         (for [[idx entry] (map-indexed vector entries)]
           ^{:key idx}
           [:div.log-entry {:class (log-entry-class (:level entry))}
            [:span.log-time (format-time (:timestamp entry))]
            [:span.log-message (:message entry)]])])]]))

;; =============================================================================
;; Tab Navigation
;; =============================================================================

(defn tab-nav []
  (let [selected @(rf/subscribe [:ui/selected-tab])]
    [:div.tab-nav
     [:button.tab
      {:class (when (= selected :orders) "active")
       :on-click #(rf/dispatch [:ui/select-tab :orders])}
      "Orders"]
     [:button.tab
      {:class (when (= selected :trades) "active")
       :on-click #(rf/dispatch [:ui/select-tab :trades])}
      "Trades"]
     [:button.tab
      {:class (when (= selected :log) "active")
       :on-click #(rf/dispatch [:ui/select-tab :log])}
      "Log"]]))

;; =============================================================================
;; Main Content
;; =============================================================================

(defn main-content []
  (let [tab @(rf/subscribe [:ui/selected-tab])]
    [:div.main-content
     (case tab
       :orders [orders-table]
       :trades [trades-table]
       :log [message-log]
       [orders-table])]))

;; =============================================================================
;; Header
;; =============================================================================

(defn header []
  [:header.header
   [:div.header-left
    [:h1 "Position Manager"]
    [status-indicator]]
   [:div.header-right
    [symbol-filter]]])

;; =============================================================================
;; Root Component
;; =============================================================================

(defn app []
  (let [theme @(rf/subscribe [:ui/theme])]
    [:div.app {:class (name theme)}
     [header]
     [stats-bar]
     [:div.content-area
      [tab-nav]
      [main-content]]]))
