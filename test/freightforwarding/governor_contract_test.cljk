(ns freightforwarding.governor-contract-test
  "The governor contract as executable tests. The single invariant
  under test:

    FreightForwardingAdvisor never commits a coordination record the
    Freight Forwarding Governor would reject, `:flag-compliance-
    concern` NEVER auto-commits at any phase, `:log-shipment-record`
    (no direct capital risk) MAY auto-commit when clean, no proposal
    that tries to finalize a customs-clearance or shipment-release
    decision ever commits (or even reaches a human), and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [freightforwarding.store :as store]
            [freightforwarding.governor :as governor]
            [freightforwarding.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :freight-forwarding-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-shipment-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-shipment-record :target-id "ship-1" :detail "customs invoice"} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/shipment-log db))))
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-logistics-operation-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-logistics-operation :target-id "ship-1"
                                   :resource-request {:route "NLRTM"}} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/schedule-log db))))))))

(deftest coordinate-carrier-booking-always-needs-approval
  (testing "carrier-booking coordination is never in any phase's :auto set -- always human approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :coordinate-carrier-booking :target-id "car-1"
                                   :booking-request "40ft-container-space"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/carrier-log db))))))))

(deftest flag-compliance-concern-always-escalates-even-when-clean
  (testing "flagging a compliance concern ALWAYS interrupts for human sign-off, even fresh/clean/high-confidence -- never auto, never a member of any phase's :auto set"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :flag-compliance-concern :target-id "ship-1"
                                   :concern-type "document-discrepancy"
                                   :description "HS code mismatch"} operator)]
      (is (= :interrupted (:status res)) "pauses for human sign-off even when governor-clean")
      (let [r2 (approve! actor "t4")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/concern-log db))))))))

(deftest unverified-shipment-is-held-and-unoverridable
  (testing "an unverified shipment -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :log-shipment-record :target-id "ship-2" :detail "packing list"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:shipment-unverified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-log db))))))

(deftest unregistered-shipment-is-held-and-unoverridable
  (testing "an unregistered shipment -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :schedule-logistics-operation :target-id "ship-3"
                                   :resource-request {:route "DEHAM"}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:shipment-unverified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-log db))))))

(deftest unregistered-carrier-is-held-and-unoverridable
  (testing "an unregistered carrier (coordinate-carrier-booking) -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :coordinate-carrier-booking :target-id "car-2"
                                   :booking-request "air-cargo-space"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:carrier-unregistered} (-> (store/ledger db) last :basis)))
      (is (empty? (store/carrier-log db))))))

(deftest missing-target-is-held
  (testing "a target-id with no shipment record at all -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :log-shipment-record :target-id "ship-nope" :detail "x"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:shipment-unverified} (-> (store/ledger db) last :basis))))))

(deftest hallucinated-op-is-held-and-unoverridable
  (testing "an op outside the closed allowlist (the advisor's own unknown-op fallback) -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t9" {:op :bogus-op :target-id "ship-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:op-not-allowed} (-> (store/ledger db) last :basis))))))

(deftest finalize-clearance-attempt-is-held-and-unoverridable
  (testing "governor/check directly: a proposal whose text tries to finalize a customs clearance -> HARD hold, defense-in-depth against a real (non-mock) advisor"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :schedule-logistics-operation :target-id "ship-1"}
                   {:actor-id "op-1"}
                   {:operation :schedule-logistics-operation :effect :propose
                    :target-id "ship-1" :confidence 0.9
                    :rationale "recommend to finalize the customs clearance now"}
                   db)]
      (is (:hard? verdict))
      (is (some #{:finalize-clearance-attempt} (map :rule (:violations verdict)))))))

(deftest shipment-release-authorization-attempt-is-held
  (testing "governor/check directly: a proposal whose text tries to authorize a shipment release -> HARD hold"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :schedule-logistics-operation :target-id "ship-1"}
                   {:actor-id "op-1"}
                   {:operation :schedule-logistics-operation :effect :propose
                    :target-id "ship-1" :confidence 0.9
                    :rationale "we should authorize the shipment release for this consignment"}
                   db)]
      (is (:hard? verdict))
      (is (some #{:finalize-clearance-attempt} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-held
  (testing "governor/check directly: a proposal whose :effect is not :propose -> HARD hold, whatever the op"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :log-shipment-record :target-id "ship-1"}
                   {:actor-id "op-1"}
                   {:operation :log-shipment-record :effect :commit
                    :target-id "ship-1" :confidence 0.9}
                   db)]
      (is (:hard? verdict))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-shipment-record :target-id "ship-1" :detail "x"} operator)
      (exec-op actor "b" {:op :log-shipment-record :target-id "ship-2" :detail "y"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ────────────── Inbound Cross-Actor Handoff (Escalate, not Hold, isic-5210 -> isic-5229) ──────────────

(def ^:private clean-shipment-with-handoff-base
  {:id "ship-h1" :shipper-name "Handoff Test Co" :destination "USLAX"
   :jurisdiction "JPN" :registered? true :verified? true})

(def ^:private well-formed-storage-handoff
  {:handoff/id "h-in-1"
   :handoff/source-actor "cloud-itonami-isic-5210"
   :handoff/batch-id "tank-batch-1"
   :handoff/product-type-id :bulk/refined-fuel
   :handoff/quantity-kg 5000.0
   :handoff/dispatched-at-iso "2026-07-24T00:00:00Z"})

(defn- store-with-handoff-shipment [handoff]
  (store/with-shipments (store/seed-db)
    {"ship-h1" (cond-> clean-shipment-with-handoff-base
                 handoff (assoc :shipment/handoff handoff))}))

(defn- handoff-verdict [db]
  (governor/check
   {:op :log-shipment-record :target-id "ship-h1"}
   {:actor-id "gov-1"}
   {:operation :log-shipment-record :effect :propose
    :target-id "ship-h1" :confidence 0.9}
   db))

(deftest storage-handoff-suspect-escalation-test
  (testing "no :shipment/handoff at all does not trigger this rule -- no violation, no escalation"
    (let [db (store-with-handoff-shipment nil)
          result (handoff-verdict db)]
      (is (false? (:hard? result)))
      (is (false? (:escalate? result)))
      (is (not (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations result))))))

  (testing "well-formed handoff from the registered upstream storage actor does not trigger this rule -- no violation, no escalation"
    (let [db (store-with-handoff-shipment well-formed-storage-handoff)
          result (handoff-verdict db)]
      (is (false? (:hard? result)))
      (is (false? (:escalate? result)))
      (is (not (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations result))))))

  (testing "handoff from an unrecognized source-actor escalates, not holds"
    (let [db (store-with-handoff-shipment (assoc well-formed-storage-handoff
                                                  :handoff/source-actor "cloud-itonami-isic-9999"))
          result (handoff-verdict db)]
      (is (false? (:hard? result)))
      (is (true? (:escalate? result)))
      (is (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations result)))))

  (testing "malformed handoff (missing quantity-kg) escalates, not holds"
    (let [db (store-with-handoff-shipment (dissoc well-formed-storage-handoff :handoff/quantity-kg))
          result (handoff-verdict db)]
      (is (false? (:hard? result)))
      (is (true? (:escalate? result)))
      (is (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations result))))))
