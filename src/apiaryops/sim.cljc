(ns apiaryops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean apiary through
  intake -> pollination-route scheduling (escalate/approve) ->
  disease-concern flag (escalate/approve) -> harvest coordination
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op, a
  route scheduled against an UNVERIFIED/unregistered site, a harvest
  coordinated against an UNVERIFIED/unregistered hive, a harvest
  proposal that would exceed the hive's own logged sustainable-harvest
  ceiling, a proposal that tries to ACTUATE a hive intervention
  directly (permanently blocked, no override), a proposal that tries
  to self-issue a disease-free/quarantine-clearance certification
  (permanently blocked, no override), a double-schedule of the same
  route, a colony-health patch with a fabricated queen-status, a
  colony-health patch with an implausible weight, and a colony-health
  patch with an implausible temperature reading.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [apiaryops.store :as store]
            [apiaryops.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :apiary-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-colony-health hive-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-colony-health :effect :propose :subject "hive-001"
                        :patch {:queen-status :laying :last-assessed "2026-07-14"}}
                       coordinator))

    (println "== schedule-pollination-route rte-1 on orchard-001 (verified, registered, orchard site -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-pollination-route :effect :propose :subject "rte-1"
                       :value {:site-id "orchard-001" :route-type :orchard-pass
                               :route-date "2026-08-01" :actuate-hive? false}}
                      coordinator)]
      (println r)
      (println "-- human apiary coordinator approves --")
      (println (approve! actor "t2")))

    (println "== flag-disease-concern concern-1 on hive-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-disease-concern :effect :propose :subject "concern-1"
                       :value {:hive-id "hive-001" :concern :suspected-varroosis
                               :description "巣底のミツバチ体表にダニ様寄生虫の付着を確認"}}
                      coordinator)]
      (println r)
      (println "-- human apiary inspector approves --")
      (println (approve! actor "t3")))

    (println "== coordinate-harvest hrv-1 on hive-001 (verified, registered, within ceiling -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :coordinate-harvest :effect :propose :subject "hrv-1"
                       :value {:hive-id "hive-001" :kg 5.0
                               :destination "local-market"}}
                      coordinator)]
      (println r)
      (println "-- human apiary coordinator approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-colony-health with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-colony-health :effect :direct-write :subject "hive-001"
                        :patch {:queen-status :laying}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :administer-hive-treatment :effect :propose :subject "hive-001"}
                       coordinator))

    (println "== schedule-pollination-route rte-2 on corridor-002 (UNVERIFIED/unregistered public corridor -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-pollination-route :effect :propose :subject "rte-2"
                        :value {:site-id "corridor-002" :route-type :corridor-sweep
                                :route-date "2026-08-01" :actuate-hive? false}}
                       coordinator))

    (println "== coordinate-harvest hrv-2 on hive-003 (UNVERIFIED/unregistered hive -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :coordinate-harvest :effect :propose :subject "hrv-2"
                        :value {:hive-id "hive-003" :kg 8.0
                                :destination "local-market"}}
                       coordinator))

    (println "== coordinate-harvest hrv-3 on hive-002 (2kg would exceed ceiling 15kg vs harvested 14kg -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :coordinate-harvest :effect :propose :subject "hrv-3"
                        :value {:hive-id "hive-002" :kg 2.0
                                :destination "local-market"}}
                       coordinator))

    (println "== schedule-pollination-route rte-3 on orchard-001 with :actuate-hive? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-pollination-route :effect :propose :subject "rte-3"
                        :value {:site-id "orchard-001" :route-type :orchard-pass
                                :route-date "2026-09-01" :actuate-hive? true}}
                       coordinator))

    (println "== schedule-pollination-route rte-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-pollination-route :effect :propose :subject "rte-1"
                        :value {:site-id "orchard-001" :route-type :orchard-pass
                                :route-date "2026-08-01" :actuate-hive? false}}
                       coordinator))

    (println "== log-colony-health hive-001 with a fabricated queen-status -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-colony-health :effect :propose :subject "hive-001"
                        :patch {:queen-status :queen-abdicated}}
                       coordinator))

    (println "== log-colony-health hive-001 with an implausible weight -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :log-colony-health :effect :propose :subject "hive-001"
                        :patch {:weight-kg 9999.0}}
                       coordinator))

    (println "== log-colony-health hive-001 with an implausible temperature reading -> HARD hold ==")
    (println (exec-op actor "t14"
                       {:op :log-colony-health :effect :propose :subject "hive-001"
                        :patch {:temperature-c 999.0}}
                       coordinator))

    (println "== log-colony-health hive-001 attempting to self-issue a disease-free/quarantine-clearance certification -> HARD hold, PERMANENT ==")
    (println (exec-op actor "t15"
                       {:op :log-colony-health :effect :propose :subject "hive-001"
                        :patch {:issue-certification? true}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft pollination-route records ==")
    (doseq [r (store/route-history db)] (println r))

    (println "\n== draft harvest records ==")
    (doseq [r (store/harvest-history db)] (println r))))
