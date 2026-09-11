(ns freightforwarding.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-compliance-concern` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [freightforwarding.phase :as phase]))

(deftest flag-compliance-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a compliance-concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-compliance-concern))
          (str "phase " n " must not auto-commit :flag-compliance-concern")))))

(deftest no-finalize-clearance-op-exists-anywhere
  (testing "structural invariant: no phase's :writes or :auto set contains any op that would finalize a customs-clearance or shipment-release decision -- no such op exists in the domain at all"
    (doseq [[n {:keys [writes auto]}] phase/phases]
      (is (= #{} (set/intersection writes #{:finalize-customs-clearance :finalize-clearance :authorize-shipment-release}))
          (str "phase " n " writes must never contain a finalize/release op"))
      (is (= #{} (set/intersection auto #{:finalize-customs-clearance :finalize-clearance :authorize-shipment-release}))
          (str "phase " n " auto must never contain a finalize/release op")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-shipment-record carries no capital risk or customs-clearance determination -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-shipment-record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-shipment-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-logistics-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-carrier-booking} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-compliance-concern} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-shipment-record} :commit)))))

(deftest gate-auto-commits-a-clean-eligible-write-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :log-shipment-record} :commit)))))
