(ns freightforwarding.scope-exclusion-test
  "Dedicated regression test for a self-tripping bug class multiple
  sibling `cloud-itonami-isic-*` actors in this fleet independently
  hit and fixed: a governor's scope-exclusion term list phrased as a
  bare noun ('customs', 'clearance', 'release') can accidentally
  match inside the mock advisor's own DEFAULT rationale/disclaimer
  text for a legitimate, allowed proposal, causing the actor to
  self-block on its own happy path.

  `freightforwarding.governor/finalize-clearance-patterns` is phrased
  as the finalization/execution ACTION ('finalize the customs
  clearance', not the bare noun 'clearance') specifically to avoid
  this. This test asserts the invariant directly: NONE of
  `freightforwarding.advisor`'s four default proposals ever trips
  `:finalize-clearance-attempt`, for every target (including the
  unverified/unregistered ones, which SHOULD hold on other rules but
  must never additionally trip the scope-exclusion check)."
  (:require [clojure.test :refer [deftest is testing]]
            [freightforwarding.store :as store]
            [freightforwarding.advisor :as advisor]
            [freightforwarding.governor :as governor]))

(defn- rule-set [verdict]
  (set (map :rule (:violations verdict))))

(deftest default-proposals-never-self-trip-scope-exclusion
  (let [db (store/seed-db)
        adv (advisor/mock-advisor)
        cases [{:op :log-shipment-record :target-id "ship-1" :detail "customs invoice received"}
               {:op :log-shipment-record :target-id "ship-2" :detail "packing list"}
               {:op :log-shipment-record :target-id "ship-3" :detail "commercial invoice"}
               {:op :schedule-logistics-operation :target-id "ship-1" :resource-request {:route "NLRTM" :consolidation "LCL"}}
               {:op :schedule-logistics-operation :target-id "ship-2" :resource-request {:route "USLAX"}}
               {:op :flag-compliance-concern :target-id "ship-1"
                :concern-type "document-discrepancy" :description "HS code on commercial invoice does not match packing list"}
               {:op :flag-compliance-concern :target-id "ship-2"
                :concern-type "missing-certificate" :description "certificate of origin not on file"}
               {:op :flag-compliance-concern :target-id "ship-3"
                :concern-type "sanctions-screen-flag" :description "consignee requires enhanced due diligence"}
               {:op :coordinate-carrier-booking :target-id "car-1" :booking-request "40ft-container-space"}
               {:op :coordinate-carrier-booking :target-id "car-2" :booking-request "air-cargo-space"}]]
    (doseq [request cases]
      (testing (str (:op request) " on " (:target-id request))
        (let [proposal (advisor/-advise adv db request)
              verdict (governor/check request {:actor-id "op-1"} proposal db)]
          (is (= :propose (:effect proposal)) "advisor always proposes, never commits directly")
          (is (not (contains? (rule-set verdict) :finalize-clearance-attempt))
              (str "legitimate default proposal must never self-trip the scope-exclusion check: "
                   (pr-str (:violations verdict))))
          (is (not (contains? (rule-set verdict) :op-not-allowed))
              "every default advisor op is in the closed allowlist")
          (is (not (contains? (rule-set verdict) :effect-not-propose))
              "the advisor's own :effect is always literally :propose"))))))

(deftest finalize-clearance-patterns-do-catch-a-real-attempt
  (testing "sanity check: the patterns are not vacuously non-matching -- they DO catch an actual finalization-action attempt"
    (let [db (store/seed-db)
          attempts
          [{:operation :schedule-logistics-operation :effect :propose :target-id "ship-1"
            :confidence 0.9 :rationale "we will finalize the customs clearance for this shipment"}
           {:operation :schedule-logistics-operation :effect :propose :target-id "ship-1"
            :confidence 0.9 :rationale "propose to authorize the shipment release now"}
           {:operation :log-shipment-record :effect :propose :target-id "ship-1"
            :confidence 0.9 :summary "通関を確定します"}]]
      (doseq [proposal attempts]
        (let [verdict (governor/check {:op (:operation proposal) :target-id "ship-1"}
                                      {:actor-id "op-1"} proposal db)]
          (is (contains? (rule-set verdict) :finalize-clearance-attempt)
              (str "must catch: " (pr-str proposal))))))))
