(ns apiaryops.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT open a hive, administer a treatment, or
  relocate a colony directly... does NOT self-issue a disease-free/
  quarantine-clearance certification') implemented faithfully. The
  single invariant under test:

    ColonyAdvisor never schedules a pollination route, flags a disease
    concern, or coordinates a harvest the Apiary Operations Governor
    would reject; `:schedule-pollination-route`/`:flag-disease-concern`/
    `:coordinate-harvest` NEVER auto-commit at any phase;
    `:log-colony-health` (no physical/financial risk) MAY auto-commit
    when clean; and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [apiaryops.store :as store]
            [apiaryops.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :apiary-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-colony-health-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-colony-health :effect :propose :subject "hive-001"
                   :patch {:queen-status :laying}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :laying (:queen-status (store/hive db "hive-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-pollination-route-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-pollination-route :effect :propose :subject "rte-1"
                     :value {:site-id "orchard-001" :route-type :orchard-pass
                             :route-date "2026-08-01" :actuate-hive? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/route db "rte-1"))))
        (is (= 1 (count (store/route-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-colony-health :effect :direct-write :subject "hive-001"
                     :patch {:queen-status :laying}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :administer-hive-treatment :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest site-not-verified-is-held-and-unoverridable
  (testing "scheduling against an unverified/unregistered site -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-pollination-route :effect :propose :subject "rte-2"
                     :value {:site-id "corridor-002" :route-type :corridor-sweep
                             :route-date "2026-08-01" :actuate-hive? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:site-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/route-history db))))))

(deftest hive-not-verified-is-held-and-unoverridable
  (testing "coordinating a harvest against an unverified/unregistered hive -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :coordinate-harvest :effect :propose :subject "hrv-2"
                     :value {:hive-id "hive-003" :kg 8.0
                             :destination "local-market"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:hive-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/harvest-history db))))))

(deftest harvest-exceeds-sustainable-yield-is-held-and-unoverridable
  (testing "a harvest proposal whose quantity would exceed the hive's own logged sustainable ceiling -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :coordinate-harvest :effect :propose :subject "hrv-3"
                     :value {:hive-id "hive-002" :kg 2.0
                             :destination "local-market"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:harvest-exceeds-sustainable-yield} (-> (store/ledger db) last :basis)))
      (is (empty? (store/harvest-history db))))))

(deftest hive-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-hive? true -> HOLD, PERMANENT, never reaches request-approval even though the site is verified and registered"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-pollination-route :effect :propose :subject "rte-3"
                     :value {:site-id "orchard-001" :route-type :orchard-pass
                             :route-date "2026-09-01" :actuate-hive? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:hive-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/route-history db))))))

(deftest certification-authority-is-held-and-permanently-blocked
  (testing "a proposal that sets :issue-certification? true -> HOLD, PERMANENT, never reaches request-approval -- this actor is never the disease-free/quarantine-clearance authority"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-colony-health :effect :propose :subject "hive-001"
                     :patch {:issue-certification? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:certification-authority-blocked} (-> (store/ledger db) last :basis)))
      (is (not (true? (:issue-certification? (store/hive db "hive-001"))))
          "fabricated self-certification never lands in the SSoT"))))

(deftest schedule-pollination-route-double-schedule-is-held
  (testing "scheduling the SAME route record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-pollination-route :effect :propose :subject "rte-1"
                                  :value {:site-id "orchard-001" :route-type :orchard-pass
                                          :route-date "2026-08-01" :actuate-hive? false}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-pollination-route :effect :propose :subject "rte-1"
                                   :value {:site-id "orchard-001" :route-type :orchard-pass
                                           :route-date "2026-08-01" :actuate-hive? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/route-history db))) "still only the one earlier schedule"))))

(deftest invalid-queen-status-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-colony-health :effect :propose :subject "hive-001"
                                  :patch {:queen-status :queen-abdicated}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-queen-status} (-> (store/ledger db) last :basis)))
    (is (not= :queen-abdicated (:queen-status (store/hive db "hive-001"))) "fabricated queen-status never lands in the SSoT")))

(deftest invalid-weight-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10b" {:op :log-colony-health :effect :propose :subject "hive-001"
                                   :patch {:weight-kg 9999.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-weight} (-> (store/ledger db) last :basis)))
    (is (not= 9999.0 (:weight-kg (store/hive db "hive-001"))) "fabricated weight never lands in the SSoT")))

(deftest invalid-temperature-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :log-colony-health :effect :propose :subject "hive-001"
                                  :patch {:temperature-c 999.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-temperature} (-> (store/ledger db) last :basis)))
    (is (not= 999.0 (:temperature-c (store/hive db "hive-001"))) "fabricated temperature never lands in the SSoT")))

(deftest disease-concern-always-escalates-even-high-confidence
  (testing "flag-disease-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :flag-disease-concern :effect :propose :subject "concern-1"
                                    :value {:hive-id "hive-001" :concern :suspected-varroosis
                                            :description "mite signs on worker bees"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t12")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/disease-concerns db))))))))

(deftest disease-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t13" {:op :flag-disease-concern :effect :propose :subject "concern-2"
                                :value {:hive-id "hive-001" :concern :suspected-nosemosis :description "y"}}
                   coordinator)
        r (reject! actor "t13")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/disease-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest coordinate-harvest-always-needs-approval
  (testing "a CLEAN harvest coordination is never auto-eligible -- always escalates, even below any ceiling threshold"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :coordinate-harvest :effect :propose :subject "hrv-1"
                                    :value {:hive-id "hive-001" :kg 5.0
                                            :destination "local-market"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t14")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/harvest-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-colony-health :effect :propose :subject "hive-001"
                          :patch {:queen-status :laying}} coordinator)
      (exec-op actor "b" {:op :log-colony-health :effect :propose :subject "hive-001"
                          :patch {:queen-status :queen-abdicated}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
