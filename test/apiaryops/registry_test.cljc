(ns apiaryops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [apiaryops.registry :as r]))

;; ----------------------------- site-verified? / site-registered? / site-ready? -----------------------------

(deftest site-is-verified-when-flagged
  (is (true? (r/site-verified? {:id "s1" :verified? true}))))

(deftest site-is-not-verified-when-false-or-missing
  (is (false? (r/site-verified? {:id "s1" :verified? false})))
  (is (false? (r/site-verified? {:id "s1"}))))

(deftest site-is-registered-when-flagged
  (is (true? (r/site-registered? {:registered? true}))))

(deftest site-is-not-registered-when-false-or-missing
  (is (false? (r/site-registered? {:registered? false})))
  (is (false? (r/site-registered? {}))))

(deftest site-ready-requires-both
  (is (true? (r/site-ready? {:verified? true :registered? true})))
  (is (false? (r/site-ready? {:verified? true :registered? false})))
  (is (false? (r/site-ready? {:verified? false :registered? true})))
  (is (false? (r/site-ready? {}))))

;; ----------------------------- hive-verified? / hive-registered? / hive-ready? -----------------------------

(deftest hive-is-verified-when-flagged
  (is (true? (r/hive-verified? {:id "h1" :verified? true}))))

(deftest hive-is-not-verified-when-false-or-missing
  (is (false? (r/hive-verified? {:id "h1" :verified? false})))
  (is (false? (r/hive-verified? {:id "h1"}))))

(deftest hive-is-registered-when-flagged
  (is (true? (r/hive-registered? {:registered? true}))))

(deftest hive-is-not-registered-when-false-or-missing
  (is (false? (r/hive-registered? {:registered? false})))
  (is (false? (r/hive-registered? {}))))

(deftest hive-ready-requires-both
  (is (true? (r/hive-ready? {:verified? true :registered? true})))
  (is (false? (r/hive-ready? {:verified? true :registered? false})))
  (is (false? (r/hive-ready? {:verified? false :registered? true})))
  (is (false? (r/hive-ready? {}))))

;; ----------------------------- harvest-exceeds-sustainable-yield? -----------------------------

(deftest small-harvest-within-ceiling-does-not-exceed
  (is (false? (r/harvest-exceeds-sustainable-yield?
               {:max-sustainable-harvest-kg 20.0 :harvested-kg-to-date 5.0} 5.0))))

(deftest harvest-that-pushes-past-ceiling-exceeds
  (is (true? (r/harvest-exceeds-sustainable-yield?
              {:max-sustainable-harvest-kg 15.0 :harvested-kg-to-date 14.0} 2.0))))

(deftest harvest-exactly-at-ceiling-does-not-exceed
  (is (false? (r/harvest-exceeds-sustainable-yield?
               {:max-sustainable-harvest-kg 15.0 :harvested-kg-to-date 14.0} 1.0))
      "exactly at ceiling is not over, only strictly beyond"))

(deftest missing-ceiling-is-not-flagged-exceeded
  (is (false? (r/harvest-exceeds-sustainable-yield? {} 100.0)))
  (is (false? (r/harvest-exceeds-sustainable-yield? {:max-sustainable-harvest-kg 20.0} nil))))

;; ----------------------------- queen-status-valid? -----------------------------

(deftest known-queen-statuses-are-valid
  (doseq [q [:laying :queen-right :queen-less :supersedure-in-progress
             :virgin-queen :queen-cell-present]]
    (is (r/queen-status-valid? q))))

(deftest fabricated-queen-status-is-invalid
  (is (not (r/queen-status-valid? :queen-abdicated)))
  (is (not (r/queen-status-valid? nil))))

;; ----------------------------- weight-valid? -----------------------------

(deftest typical-weight-is-valid
  (is (r/weight-valid? 0))
  (is (r/weight-valid? 42.0))
  (is (r/weight-valid? 150)))

(deftest negative-weight-is-invalid
  (is (not (r/weight-valid? -1))))

(deftest excessive-weight-is-invalid
  (is (not (r/weight-valid? 9999.0)))
  (is (not (r/weight-valid? 151))))

(deftest non-numeric-or-missing-weight-is-invalid
  (is (not (r/weight-valid? nil)))
  (is (not (r/weight-valid? "42"))))

;; ----------------------------- temperature-valid? -----------------------------

(deftest typical-temperature-is-valid
  (is (r/temperature-valid? -20))
  (is (r/temperature-valid? 35.0))
  (is (r/temperature-valid? 50)))

(deftest excessive-temperature-is-invalid
  (is (not (r/temperature-valid? 999.0)))
  (is (not (r/temperature-valid? 51))))

(deftest too-cold-temperature-is-invalid
  (is (not (r/temperature-valid? -21))))

(deftest non-numeric-or-missing-temperature-is-invalid
  (is (not (r/temperature-valid? nil)))
  (is (not (r/temperature-valid? "35"))))

;; ----------------------------- register-route -----------------------------

(deftest route-is-a-draft-not-a-real-dispatch
  (let [result (r/register-route "rte-1" "orchard-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest route-assigns-route-number
  (let [result (r/register-route "rte-1" "orchard-001" 7)]
    (is (= (get result "route_number") "RTE-000007"))
    (is (= (get-in result ["record" "route_id"]) "rte-1"))
    (is (= (get-in result ["record" "site_id"]) "orchard-001"))
    (is (= (get-in result ["record" "kind"]) "pollination-route-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest route-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-route "" "orchard-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-route "rte-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-route "rte-1" "orchard-001" -1))))

;; ----------------------------- register-harvest -----------------------------

(deftest harvest-is-a-draft-not-a-real-extraction
  (let [result (r/register-harvest "hrv-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest harvest-assigns-harvest-number
  (let [result (r/register-harvest "hrv-1" 7)]
    (is (= (get result "harvest_number") "HRV-000007"))
    (is (= (get-in result ["record" "harvest_id"]) "hrv-1"))
    (is (= (get-in result ["record" "kind"]) "harvest-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest harvest-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-harvest "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-harvest "hrv-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-route "rte-1" "orchard-001" 0)
        hist (r/append [] c1)
        c2 (r/register-route "rte-2" "orchard-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "RTE-000000" (get-in hist2 [0 "record_id"])))
    (is (= "RTE-000001" (get-in hist2 [1 "record_id"])))))
