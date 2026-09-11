(ns apiaryops.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `apiaryops.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [apiaryops.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/hive s "hive-001"))))
    (is (true? (:registered? (store/hive s "hive-001"))))
    (is (true? (:verified? (store/hive s "hive-002"))))
    (is (true? (:registered? (store/hive s "hive-002"))))
    (is (false? (:verified? (store/hive s "hive-003"))))
    (is (false? (:registered? (store/hive s "hive-003"))))
    (is (= ["hive-001" "hive-002" "hive-003"] (mapv :id (store/all-hives s))))
    (is (true? (:verified? (store/site s "orchard-001"))))
    (is (true? (:registered? (store/site s "orchard-001"))))
    (is (false? (:verified? (store/site s "corridor-002"))))
    (is (false? (:registered? (store/site s "corridor-002"))))
    (is (= ["corridor-002" "orchard-001"] (mapv :id (store/all-sites s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/route-history s)))
    (is (= [] (store/harvest-history s)))
    (is (= [] (store/disease-concerns s)))
    (is (zero? (store/next-route-sequence s)))
    (is (zero? (store/next-harvest-sequence s)))
    (is (false? (store/route-already-scheduled? s "rte-1")))
    (is (nil? (store/route s "rte-1")))))

(deftest fresh-store-has-no-hives-or-sites
  (let [s (store/mem-store)]
    (is (= [] (store/all-hives s)))
    (is (nil? (store/hive s "hive-001")))
    (is (= [] (store/all-sites s)))
    (is (nil? (store/site s "orchard-001")))))

(deftest hive-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :hive/upsert :path ["hive-001"]
                             :value {:queen-status :queen-right}})
    (is (= :queen-right (:queen-status (store/hive s "hive-001"))))
    (is (true? (:verified? (store/hive s "hive-001"))) "unrelated field preserved")
    (is (true? (:registered? (store/hive s "hive-001"))) "unrelated field preserved")))

(deftest route-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :route/schedule :path ["rte-1"]
                               :value {:site-id "orchard-001" :route-type :orchard-pass
                                       :route-date "2026-08-01"}})
      (is (= "RTE-000000" (get (first (store/route-history s)) "record_id")))
      (is (= "pollination-route-schedule-draft" (get (first (store/route-history s)) "kind")))
      (is (true? (:scheduled? (store/route s "rte-1"))))
      (is (= "orchard-001" (:site-id (store/route s "rte-1"))))
      (is (= 1 (count (store/route-history s))))
      (is (= 1 (store/next-route-sequence s)))
      (is (true? (store/route-already-scheduled? s "rte-1")))
      (is (= "RTE-000000" (:route-number (store/route s "rte-1")))))))

(deftest disease-concern-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :disease-concern/flag :path ["concern-1"]
                             :value {:hive-id "hive-001" :concern :suspected-varroosis}})
    (is (= 1 (count (store/disease-concerns s))))
    (is (= :suspected-varroosis (:concern (first (store/disease-concerns s)))))
    (store/commit-record! s {:effect :disease-concern/flag :path ["concern-2"]
                             :value {:hive-id "hive-002" :concern :suspected-american-foulbrood}})
    (is (= 2 (count (store/disease-concerns s))) "append-only")))

(deftest harvest-propose-commits-and-advances-sequence-and-hive-quantity
  (let [s (seeded)]
    (store/commit-record! s {:effect :harvest/propose :path ["hrv-1"]
                             :value {:hive-id "hive-001" :kg 5.0
                                     :destination "local-market"}})
    (is (= "HRV-000000" (get (first (store/harvest-history s)) "record_id")))
    (is (= "harvest-coordination-draft" (get (first (store/harvest-history s)) "kind")))
    (is (= 1 (count (store/harvest-history s))))
    (is (= 1 (store/next-harvest-sequence s)))
    (is (= "HRV-000000" (:harvest-number (store/harvest s "hrv-1"))))
    (is (= 10.0 (:harvested-kg-to-date (store/hive s "hive-001")))
        "5.0 seeded + 5.0 committed")))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
