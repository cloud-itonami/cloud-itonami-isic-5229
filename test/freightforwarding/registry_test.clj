(ns freightforwarding.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [freightforwarding.registry :as registry]))

(deftest register-shipment-record-drafts-a-record
  (let [r (registry/register-shipment-record "ship-1" "JPN" 0)]
    (is (= "JPN-SHIPMENT-000000" (get r "record_number")))
    (is (= "JPN-SHIPMENT-000000" (get-in r ["record" "record_id"])))
    (is (= "shipment-log-draft" (get-in r ["record" "kind"])))
    (is (true? (get-in r ["record" "immutable"])))
    (is (false? (get-in r ["certificate" "issued_by_registry"])) "never a real registry issuance")))

(deftest register-schedule-record-drafts-a-record
  (let [r (registry/register-schedule-record "ship-1" "JPN" 3)]
    (is (= "JPN-SCHEDULE-000003" (get r "record_number")))
    (is (= "schedule-proposal-draft" (get-in r ["record" "kind"])))))

(deftest register-carrier-coordination-record-drafts-a-record
  (let [r (registry/register-carrier-coordination-record "car-1" "JPN" 0)]
    (is (= "JPN-CARRIER-000000" (get r "record_number")))
    (is (= "carrier-coordination-draft" (get-in r ["record" "kind"])))))

(deftest register-concern-record-drafts-a-record
  (let [r (registry/register-concern-record "ship-1" "JPN" 0)]
    (is (= "JPN-CONCERN-000000" (get r "record_number")))
    (is (= "compliance-concern-draft" (get-in r ["record" "kind"])))))

(deftest missing-target-id-throws
  (testing "honest failure -- never silently drafts a record with no target"
    (is (thrown? Exception
                 (registry/register-shipment-record nil "JPN" 0)))
    (is (thrown? Exception
                 (registry/register-shipment-record "" "JPN" 0)))))

(deftest missing-jurisdiction-throws
  (is (thrown? Exception
               (registry/register-shipment-record "ship-1" nil 0)))
  (is (thrown? Exception
               (registry/register-shipment-record "ship-1" "" 0))))

(deftest negative-sequence-throws
  (is (thrown? Exception
               (registry/register-shipment-record "ship-1" "JPN" -1))))

(deftest append-conj-record-onto-history
  (let [r (registry/register-shipment-record "ship-1" "JPN" 0)]
    (is (= [(get r "record")] (registry/append [] r)))
    (is (= [:x (get r "record")] (registry/append [:x] r)))))

;; ───────── Inbound Cross-Actor Handoff (this actor as RECEIVER, isic-5210 -> isic-5229) ─────────

(def ^:private well-formed-handoff
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-isic-5210"
   :handoff/batch-id "tank-batch-1"
   :handoff/product-type-id :bulk/refined-fuel
   :handoff/quantity-kg 5000.0
   :handoff/dispatched-at-iso "2026-07-24T00:00:00Z"})

(deftest handoff-record-well-formed-test
  (testing "complete handoff passes"
    (is (true? (registry/handoff-record-well-formed? well-formed-handoff))))

  (testing "missing :handoff/quantity-kg fails"
    (is (false? (registry/handoff-record-well-formed? (dissoc well-formed-handoff :handoff/quantity-kg)))))

  (testing "non-positive quantity fails"
    (is (false? (registry/handoff-record-well-formed? (assoc well-formed-handoff :handoff/quantity-kg 0)))))

  (testing "blank batch-id fails"
    (is (false? (registry/handoff-record-well-formed? (assoc well-formed-handoff :handoff/batch-id "")))))

  (testing "nil handoff fails"
    (is (false? (registry/handoff-record-well-formed? nil)))))

(deftest storage-handoff-source-actor-known-test
  (testing "the registered upstream storage/terminal actor is known"
    (is (true? (registry/storage-handoff-source-actor-known? "cloud-itonami-isic-5210"))))

  (testing "an unrecognized source-actor is not known"
    (is (false? (registry/storage-handoff-source-actor-known? "cloud-itonami-isic-9999"))))

  (testing "nil source-actor is not known"
    (is (false? (registry/storage-handoff-source-actor-known? nil)))))
