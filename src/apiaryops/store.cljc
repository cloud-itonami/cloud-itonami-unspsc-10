(ns apiaryops.store
  "SSoT for the independent urban apiary and pollinator-services
  coordination actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every `cloud-itonami-*` actor in
  this fleet uses.

  Scope note: like its siblings (`cloud-itonami-isic-3091`'s own
  `motomfg.store`), this build ships a single `MemStore` backend only
  (atom of EDN) -- the deterministic default for dev/tests/demo, no
  deps. Per docs/adr/0001-architecture.md Decision 1, this vertical is
  self-contained (no external apiary-management capability library, no
  jurisdiction-scoped Datomic-parity requirement driving a second
  backend); a `langchain.db`-backed store can be added later behind the
  same protocol without changing any caller.

  Four kinds of entity live here:
    - `hives`             -- the central entity. A managed colony's
                             own queen-status/weight/temperature/
                             acoustic-signal record. `:verified?` marks
                             whether the hive's own claims have
                             actually been inspected (never inferred
                             from a routine intake patch);
                             `:registered?` marks whether it is on file
                             in the operator's colony registry;
                             `:harvested-kg-to-date` tracks the hive's
                             own cumulative-harvested ground truth.
    - `sites`              -- a pollination-service site's own record
                             (farm/garden/orchard/campus/corridor).
                             `:verified?`/`:registered?` track whether
                             it has actually been surveyed/registered
                             and is on file -- the same ground-truth
                             discipline as `hives`.
    - `routes`             -- a scheduled pollination-route DRAFT
                             against a site (`apiaryops.registry`'s
                             `register-route`). Dedicated `:scheduled?`
                             double-schedule guard (never a `:status`
                             value -- the same discipline every prior
                             governor's guards establish, informed by
                             `cloud-itonami-isic-6492`'s
                             status-lifecycle bug, ADR-2607071320).
    - `harvests`           -- a proposed honey/wax harvest DRAFT
                             (`apiaryops.registry`'s `register-harvest`).

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which hive was logged, which route
  was scheduled against a verified/registered site, which harvest was
  coordinated and at what independently-recomputed cumulative
  quantity, approved by whom, which disease concern was flagged' is
  always a query over an immutable log -- the audit trail an operator
  or downstream customer trusting this coordinator needs."
  (:require [apiaryops.registry :as registry]))

(defprotocol Store
  (hive [s id])
  (all-hives [s])
  (site [s id])
  (all-sites [s])
  (route [s id])
  (all-routes [s])
  (harvest [s id])
  (disease-concerns [s] "the append-only disease-concern log")
  (ledger [s])
  (route-history [s] "the append-only pollination-route-schedule history (apiaryops.registry drafts)")
  (harvest-history [s] "the append-only harvest-coordination history (apiaryops.registry drafts)")
  (next-route-sequence [s] "next route-number sequence")
  (next-harvest-sequence [s] "next harvest-number sequence")
  (route-already-scheduled? [s route-id] "has this pollination route already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-hives [s hives] "replace/seed the hive directory (map id->hive)")
  (with-sites [s sites] "replace/seed the site directory (map id->site)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-hives []
  {"hive-001" {:id "hive-001" :queen-status :laying
               :weight-kg 42.0 :temperature-c 35.0 :acoustic-signal :calm
               :verified? true :registered? true
               :max-sustainable-harvest-kg 20.0 :harvested-kg-to-date 5.0
               :last-assessed "2026-06-01"}
   "hive-002" {:id "hive-002" :queen-status :queen-right
               :weight-kg 38.0 :temperature-c 34.0 :acoustic-signal :calm
               :verified? true :registered? true
               :max-sustainable-harvest-kg 15.0 :harvested-kg-to-date 14.0
               :last-assessed "2026-06-01"}
   "hive-003" {:id "hive-003" :queen-status :queen-less
               :weight-kg 30.0 :temperature-c 33.0 :acoustic-signal :queenless-piping
               :verified? false :registered? false
               :max-sustainable-harvest-kg 18.0 :harvested-kg-to-date 0.0
               :last-assessed "2026-05-15"}})

(defn- sample-sites []
  {"orchard-001" {:id "orchard-001" :kind :orchard
                  :verified? true :registered? true
                  :last-route-date "2026-05-01"}
   "corridor-002" {:id "corridor-002" :kind :public-corridor
                   :verified? false :registered? false
                   :last-route-date nil}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-route!
  "Backend-agnostic `:route/schedule` -- drafts the pollination-route-
  schedule record via `apiaryops.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s route-id site-id]
  (let [seq-n (next-route-sequence s)
        result (registry/register-route route-id site-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :route-number (get result "route_number")}}))

(defn- propose-harvest!
  "Backend-agnostic `:harvest/propose` -- drafts the harvest-
  coordination record via `apiaryops.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s harvest-id]
  (let [seq-n (next-harvest-sequence s)
        result (registry/register-harvest harvest-id seq-n)]
    {:result result
     :patch {:harvest-number (get result "harvest_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (hive [_ id] (get-in @a [:hives id]))
  (all-hives [_] (sort-by :id (vals (:hives @a))))
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (route [_ id] (get-in @a [:routes id]))
  (all-routes [_] (sort-by :id (vals (:routes @a))))
  (harvest [_ id] (get-in @a [:harvests id]))
  (disease-concerns [_] (:disease-concerns @a))
  (ledger [_] (:ledger @a))
  (route-history [_] (:route-history @a))
  (harvest-history [_] (:harvest-history @a))
  (next-route-sequence [_] (:route-sequence @a 0))
  (next-harvest-sequence [_] (:harvest-sequence @a 0))
  (route-already-scheduled? [_ route-id]
    (boolean (get-in @a [:routes route-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :hive/upsert)
      (swap! a update-in [:hives (first path)] merge (assoc value :id (first path)))

      (= effect :route/schedule)
      (let [route-id (first path)
            site-id (:site-id value)
            {:keys [result patch]} (schedule-route! s route-id site-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :route-sequence (fnil inc 0))
                       (update-in [:routes route-id] merge (assoc value :id route-id) patch)
                       (update :route-history registry/append result)
                       (update-in [:sites site-id :last-scheduled-route-date]
                                  (fn [_prev] (:route-date value))))))
        result)

      (= effect :disease-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :disease-concerns conj concern)
        concern)

      (= effect :harvest/propose)
      (let [harvest-id (first path)
            hive-id (:hive-id value)
            {:keys [result patch]} (propose-harvest! s harvest-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :harvest-sequence (fnil inc 0))
                       (update-in [:harvests harvest-id] merge (assoc value :id harvest-id) patch)
                       (update :harvest-history registry/append result)
                       (update-in [:hives hive-id :harvested-kg-to-date]
                                  (fn [prev]
                                    (+ (double (or prev 0.0))
                                       (double (or (:kg value) 0.0))))))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `logging`-style siblings expose as their own
      ;; low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-hives [s hives] (when (seq hives) (swap! a assoc :hives hives)) s)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:hives {} :sites {} :routes {} :harvests {}
                      :records {} :disease-concerns []
                      :ledger [] :route-sequence 0 :route-history []
                      :harvest-sequence 0 :harvest-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained hive + site set
  -- one verified+registered hive with harvest headroom (schedulable),
  one verified+registered hive that is nearly fully harvested (a small
  new harvest blows through its own logged sustainable ceiling -- HARD
  hold), one UNVERIFIED/unregistered hive (blocks any harvest
  coordinated against it); one verified+registered orchard site
  (schedulable for a pollination route), one UNVERIFIED/unregistered
  public-corridor site (blocks any route scheduling against it) -- so
  the actor + demo + tests run offline. Returns `s` (thread-friendly
  with `->`)."
  [s]
  (with-hives s (sample-hives))
  (with-sites s (sample-sites))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
