(ns freightforwarding.advisor
  "FreightForwardingAdvisor -- the *contained intelligence node* for
  the freight-forwarding/customs-brokerage operations-coordination
  actor.

  It drafts freight-forwarding/customs-documentation service-log
  entries, routing/consolidation scheduling proposals, carrier-
  booking coordination proposals, and customs-documentation/
  regulatory-compliance-concern flags. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and
  never a customs-clearance decision or a shipment-release
  authorization. Every output is censored downstream by
  `freightforwarding.governor` before anything touches the SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  IMPORTANT (self-tripping-bug discipline, this repo's own governor
  history -- see `freightforwarding.governor`): none of the rationale
  text below uses a finalize/authorize/release/waive/grant/issue verb
  next to 'customs clearance'/'shipment release'/'customs
  inspection'/'goods through customs' -- every disclaimer is phrased
  as what this proposal does NOT do, using verbs the governor's own
  scope-exclusion patterns do not scan for, so a legitimate default
  proposal never matches its own governor's finalization-action
  patterns. `test/freightforwarding/scope_exclusion_test.clj` asserts
  this directly for every op below.

  Proposal shape (all ops):
    {:operation  kw             ; one of the closed op-allowlist
     :effect     :propose       ; ALWAYS :propose -- structurally checked too
     :target-id  str            ; the shipment/carrier id
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :confidence 0..1}"
  (:require [freightforwarding.store :as store]))

(defn advise-log-shipment-record
  "Draft a freight-forwarding/customs-documentation SHIPMENT-LOG
  proposal -- administrative data logging only."
  [db {:keys [target-id detail]}]
  (let [c (store/shipment db target-id)]
    {:operation :log-shipment-record
     :effect :propose
     :target-id target-id
     :detail detail
     :summary (str target-id " の貨物運送/通関書類記録ログ提案")
     :rationale "Administrative logging of freight-forwarding/customs-documentation data only; no customs-clearance determination is made by this proposal."
     :confidence (if c 0.95 0.2)}))

(defn advise-schedule-logistics-operation
  "Draft a routing/consolidation SCHEDULING proposal -- a proposal
  only."
  [db {:keys [target-id resource-request]}]
  (let [c (store/shipment db target-id)]
    {:operation :schedule-logistics-operation
     :effect :propose
     :target-id target-id
     :resource-request resource-request
     :summary (str target-id " 向け輸送経路/混載スケジューリング提案")
     :rationale "Routing/consolidation scheduling proposal only; does not commit any shipment-release decision and makes no customs-clearance determination."
     :confidence (if c 0.9 0.2)}))

(defn advise-flag-compliance-concern
  "Draft a customs-documentation/regulatory-compliance-concern flag --
  ALWAYS escalates to human sign-off. This is a REAL-WORLD
  compliance-relevant signal (a reported documentation discrepancy or
  regulatory-compliance gap), never a draft the actor may auto-run
  and never itself a customs-clearance or shipment-release status
  change. See `freightforwarding.phase`: no phase ever adds this op
  to a phase's `:auto` set; the governor also always escalates on
  this op. Two independent layers agree, deliberately."
  [db {:keys [target-id concern-type description]}]
  (let [c (store/shipment db target-id)]
    {:operation :flag-compliance-concern
     :effect :propose
     :target-id target-id
     :concern-type concern-type
     :description description
     :summary (str target-id " について通関/規制コンプライアンス上の懸念(" concern-type ")を提起")
     :rationale "Surfaces a customs-documentation/regulatory-compliance concern for mandatory human review; takes no independent action on customs-clearance or shipment-release status."
     :confidence (if c 0.98 0.5)}))

(defn advise-coordinate-carrier-booking
  "Draft a CARRIER-BOOKING coordination proposal -- coordination
  only, never a shipment-release authorization."
  [db {:keys [target-id booking-request]}]
  (let [c (store/carrier db target-id)]
    {:operation :coordinate-carrier-booking
     :effect :propose
     :target-id target-id
     :booking-request booking-request
     :summary (str target-id " の運送業者ブッキング調整(" booking-request ")提案")
     :rationale "Carrier-booking coordination only; does not commit any shipment-release decision or customs-clearance determination."
     :confidence (if c 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :target-id id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-shipment-record          (advise-log-shipment-record db request)
    :schedule-logistics-operation (advise-schedule-logistics-operation db request)
    :flag-compliance-concern      (advise-flag-compliance-concern db request)
    :coordinate-carrier-booking   (advise-coordinate-carrier-booking db request)
    {:operation :noop :effect :propose :target-id nil
     :summary "未対応の操作" :rationale (str op) :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :target-id  (:target-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :confidence (:confidence proposal)})
