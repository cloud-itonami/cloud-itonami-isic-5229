(ns freightforwarding.spec-basis-test
  "Check 5 (`:no-spec-basis`) and the licence-evidence soft gate, as
  executable tests.

  This actor coordinates freight forwarding and customs brokerage --
  licensed activities almost everywhere. Creating an official
  coordination record against a jurisdiction whose regime the actor has
  no registered basis for must surface, not proceed. And because a
  refusal that never fires is indistinguishable from no refusal at all,
  every case below is paired: the unknown jurisdiction holds, the known
  one does not."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [freightforwarding.store :as store]
            [freightforwarding.governor :as governor]
            [freightforwarding.facts :as facts]
            [freightforwarding.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :freight-forwarding-operator :phase 3})

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- rules [res] (set (map :rule (get-in res [:state :verdict :violations]))))
(defn- soft-rules [res] (set (map :rule (get-in res [:state :verdict :soft-violations]))))

;; ---------------------------------------------------------------- check 5

(deftest unknown-jurisdiction-shipment-holds
  (testing "ship-4 is registered AND verified -- only its jurisdiction is unknown,
            so :no-spec-basis is the ONLY hard violation"
    (let [[db actor] (fresh)
          res (exec-op actor "s1" {:op :log-shipment-record :target-id "ship-4"
                                   :detail "commercial invoice"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= #{:no-spec-basis} (rules res)))
      (testing "nothing was written to the record log, only the ledger"
        (is (= 0 (count (store/shipment-log db))))
        (is (= 1 (count (store/ledger db))))))))

(deftest unknown-jurisdiction-carrier-holds
  (testing "check 5 applies to the carrier-level op too, not only to shipments"
    (let [[_ actor] (fresh)
          res (exec-op actor "s2" {:op :coordinate-carrier-booking :target-id "car-3"
                                   :detail "space booking"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= #{:no-spec-basis} (rules res))))))

(deftest known-jurisdiction-does-not-trip-check-5
  (testing "the paired negative -- ship-1 is JPN, which IS in the catalog"
    (let [[_ actor] (fresh)
          res (exec-op actor "s3" {:op :log-shipment-record :target-id "ship-1"
                                   :detail "customs invoice"} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not (contains? (rules res) :no-spec-basis))))))

(deftest a-missing-record-reports-only-verification-not-spec-basis
  (testing "check 5 needs a record to read a jurisdiction FROM. A target that
            does not exist at all is check 1's business, and check 5 must not
            invent a second violation for it"
    (let [[_ actor] (fresh)
          res (exec-op actor "s4" {:op :log-shipment-record :target-id "ship-nope"
                                   :detail "x"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= #{:shipment-unverified} (rules res))))))

(deftest checks-1-and-5-report-independently
  (testing "an unverified record in an unknown jurisdiction reports BOTH --
            a governor that stops at the first violation teaches the operator
            to fix one thing at a time"
    (let [db (store/with-shipments
               (store/seed-db)
               {"ship-x" {:id "ship-x" :shipper-name "Both Wrong Ltd"
                          :destination "XXXXX" :jurisdiction "ATL"
                          :registered? true :verified? false}})
          actor (op/build db)
          res (exec-op actor "s5" {:op :log-shipment-record :target-id "ship-x"
                                   :detail "x"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= #{:shipment-unverified :no-spec-basis} (rules res))))))

(deftest spec-basis-is-re-derived-from-the-store-not-the-proposal
  (testing "a proposal that self-reports a known jurisdiction cannot launder an
            unknown one -- the governor reads the STORE"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :log-shipment-record :target-id "ship-4"}
                   operator
                   {:effect :propose :operation :log-shipment-record
                    :target-id "ship-4" :confidence 0.95
                    :jurisdiction "JPN"            ; <- the lie
                    :cites (facts/sources "JPN")
                    :summary "s" :rationale "r"}
                   db)]
      (is (true? (:hard? verdict)))
      (is (contains? (set (map :rule (:violations verdict))) :no-spec-basis)))))

;; ---------------------------------------------------------------- soft gate

(deftest incomplete-licence-evidence-escalates-but-does-not-hold
  (testing "ship-5 is clean except that its ATTACHED checklist is short of JPN's
            requirement -- escalate, never hold"
    (let [[_ actor] (fresh)
          res (exec-op actor "s6" {:op :log-shipment-record :target-id "ship-5"
                                   :detail "packing list"} operator)]
      (is (= :escalate (get-in res [:state :disposition])))
      (is (false? (get-in res [:state :verdict :hard?])))
      (is (= #{:licence-evidence-incomplete} (soft-rules res))))))

(deftest absence-of-a-checklist-is-never-flagged
  (testing "most coordination records carry no checklist at all; flagging that
            would make the actor useless"
    (let [[_ actor] (fresh)
          res (exec-op actor "s7" {:op :log-shipment-record :target-id "ship-1"
                                   :detail "customs invoice"} operator)]
      (is (empty? (soft-rules res)))
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest a-complete-checklist-passes
  (let [db (store/with-shipments
             (store/seed-db)
             {"ship-ok" {:id "ship-ok" :shipper-name "Full Papers KK"
                         :destination "NLRTM" :jurisdiction "JPN"
                         :registered? true :verified? true
                         :compliance-checklist (facts/required-evidence "JPN")}})
        actor (op/build db)
        res (exec-op actor "s8" {:op :log-shipment-record :target-id "ship-ok"
                                 :detail "customs invoice"} operator)]
    (is (empty? (soft-rules res)))
    (is (= :commit (get-in res [:state :disposition])))))

(deftest an-empty-checklist-is-present-and-therefore-checked
  (testing "#{} is present, not absent -- it must escalate, not slip through"
    (let [db (store/with-shipments
               (store/seed-db)
               {"ship-e" {:id "ship-e" :shipper-name "No Papers Ltd"
                          :destination "NLRTM" :jurisdiction "JPN"
                          :registered? true :verified? true
                          :compliance-checklist #{}}})
          actor (op/build db)
          res (exec-op actor "s9" {:op :log-shipment-record :target-id "ship-e"
                                   :detail "customs invoice"} operator)]
      (is (= #{:licence-evidence-incomplete} (soft-rules res)))
      (is (= :escalate (get-in res [:state :disposition]))))))
