(ns freightforwarding.facts-test
  "The facts catalog is the governor's only source of jurisdictional
  truth (check 5), so its invariants are pinned here: an unknown
  jurisdiction must yield NOTHING, every entry must carry a real
  citation, and coverage must be reported honestly rather than implied."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [freightforwarding.facts :as facts]))

(deftest unknown-jurisdiction-yields-nothing
  (testing "absence of a rule is not permission"
    (is (nil? (facts/jurisdiction "ATL")))
    (is (false? (facts/spec-basis-known? "ATL")))
    (is (nil? (facts/required-evidence "ATL")))
    (is (= [] (facts/sources "ATL")))
    (is (false? (facts/required-evidence-satisfied?
                 "ATL" #{:forwarder-registration :customs-broker-licence
                         :financial-security})))
    (testing "even an EMPTY checklist does not satisfy an unknown jurisdiction"
      (is (false? (facts/required-evidence-satisfied? "ATL" #{}))))))

(deftest missing-or-blank-jurisdiction-is-never-known
  (testing "a record with no jurisdiction must not slip through as 'known'"
    (is (false? (facts/spec-basis-known? nil)))
    (is (false? (facts/spec-basis-known? "")))
    (is (false? (facts/spec-basis-known? "   ")))
    (is (false? (facts/spec-basis-known? :JPN)))))

(deftest every-entry-carries-a-real-citation
  (doseq [[iso3 entry] facts/catalog]
    (testing iso3
      (is (seq (:sources entry)) "an entry with no source is not a spec-basis")
      (is (every? #(str/starts-with? % "https://") (:sources entry)))
      (is (seq (:legal-basis entry)))
      (is (seq (:owner-authority entry)))
      (is (seq (:forwarder-regime entry)))
      (is (seq (:broker-regime entry)))
      (is (seq (:provenance entry)))
      (is (seq (:required-evidence entry))))))

(deftest known-jurisdictions-resolve
  (doseq [iso3 ["JPN" "USA" "EUR" "GBR" "KOR"]]
    (is (true? (facts/spec-basis-known? iso3)) iso3)
    (is (seq (facts/sources iso3)) iso3)))

(deftest evidence-is-all-or-nothing
  (let [req (facts/required-evidence "JPN")]
    (is (true? (facts/required-evidence-satisfied? "JPN" req)))
    (testing "a superset still satisfies"
      (is (true? (facts/required-evidence-satisfied?
                  "JPN" (conj (set req) :extra-thing)))))
    (testing "dropping any single required item fails"
      (doseq [k req]
        (is (false? (facts/required-evidence-satisfied? "JPN" (disj (set req) k)))
            (str "dropping " k " must fail"))))))

(deftest coverage-is-reported-honestly
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:count c)))
    (is (= (set (keys facts/catalog)) (set (:jurisdictions c))))
    (testing "the note says what the catalog is NOT"
      (is (str/includes? (:note c) "spec-basis")))))

(deftest jpn-law-ids-are-the-ones-the-docstring-claims
  (testing "the two e-Gov law IDs were resolved and their titles confirmed;
            if someone edits them, this test says which is which"
    (let [srcs (set (facts/sources "JPN"))]
      ;; 貨物利用運送事業法 = 平成元年法律第82号
      (is (contains? srcs "https://elaws.e-gov.go.jp/document?lawid=401AC0000000082"))
      ;; 通関業法 = 昭和42年法律第122号
      (is (contains? srcs "https://elaws.e-gov.go.jp/document?lawid=342AC0000000122")))))
