(ns apiaryops.registry
  "Pure-function domain logic for the independent urban apiary and
  pollinator-services coordination actor -- site/hive verification,
  cumulative harvest-quantity recompute, queen-status validation,
  hive-weight plausibility validation, and hive-temperature
  plausibility validation, plus draft pollination-route-schedule/
  harvest-coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/apiaryops`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `apiaryops.governor` -- the same 'ground truth, not self-report'
  discipline established across this fleet (most directly
  `cloud-itonami-isic-3091`'s `motomfg.registry`): never trust a
  proposal's own self-reported cumulative harvest quantity when the
  inputs needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real apiary-management system. It builds the DRAFT
  record a pollination-service coordinator would keep (a scheduled
  pollination route, a coordinated harvest), not the act of opening a
  hive, administering a treatment, relocating a colony, or issuing a
  disease-free/quarantine-clearance certification (this actor NEVER
  does any of those -- see README `What this actor does NOT do`).

  SCOPE NOTE: `cloud-itonami-isco-6123` (ISCO-08 6123, Apiarists and
  Sericulturists) already implements an `apiary.governor` named
  `ApiaryOperationsGovernor` (same governor keyword, an existing
  fleet-wide overlap between the occupation classification and this
  product/service classification -- both legitimately describe apiary
  work, from different axes). That build is a single-harvest-vs-ceiling
  arithmetic check for an individual apiarist's own hive-harvest
  approval; it has no pollination-ROUTE service, no site entity, no
  cumulative harvested-to-date tracking, and no notifiable-disease
  escalation. This vertical is a DIFFERENT, richer coordination
  surface -- a managed pollination-SERVICE operator's site/hive
  registry, cumulative-harvest recompute (mirroring
  `motomfg.registry/shipment-quantity-exceeded?`'s cumulative-shipped
  pattern, not a single-value ceiling check), and WOAH-notifiable-
  disease-grounded concern flagging -- see docs/adr/0001-architecture.md
  Decision 1 for the full differentiation record.")

;; ----------------------------- constants -----------------------------

(def valid-queen-statuses
  "The closed set of queen-status values a colony-health record may
  declare. Anything else is a fabricated/unrecognized status -- the
  governor HARD-holds rather than let an invented status through."
  #{:laying :queen-right :queen-less :supersedure-in-progress
    :virgin-queen :queen-cell-present})

(def valid-acoustic-signals
  "The closed set of acoustic swarm-signal classifications a
  hive-monitoring robot's sensor read may report."
  #{:calm :swarming :queenless-piping :defensive :absconding-risk})

(def valid-disease-concerns
  "The closed set of notifiable/reportable bee-health concerns this
  actor may flag, drawn from WOAH (World Organisation for Animal
  Health, formerly OIE) Terrestrial Animal Health Code Chapter 9.2
  (bee diseases) plus the well-documented Varroa/Tropilaelaps mite
  concerns that chapter also lists -- verified 2026-07-19 (WOAH
  'Diseases of bees', six listed bee diseases, American foulbrood
  notifiable in most member countries). This actor NEVER diagnoses or
  certifies any of these -- flagging is a suspicion report that always
  escalates to a human apiary inspector / animal-health authority (see
  README `What this actor does NOT do`)."
  #{:suspected-american-foulbrood :suspected-european-foulbrood
    :suspected-varroosis :suspected-small-hive-beetle
    :suspected-tropilaelaps :suspected-nosemosis :colony-collapse-signs})

(def weight-min-kg
  "Physical floor for a hive's own total (boxes + bees + stores)
  weight reading."
  0)

(def weight-max-kg
  "Physical ceiling for a hive's own total weight reading -- a
  multi-super Langstroth hive at full honey flow rarely exceeds this;
  a reading above it is implausible/fabricated sensor data, not a
  real hive."
  150)

(def temperature-min-c
  "Physical floor for a hive-interior temperature sensor reading
  (ambient winter cold, not the biologically-regulated brood-nest
  optimum -- this is a SENSOR PLAUSIBILITY bound, not an optimality
  judgement)."
  -20)

(def temperature-max-c
  "Physical ceiling for a hive-interior temperature sensor reading -- a
  reading above this is implausible/fabricated sensor data (a healthy
  hive actively thermoregulates below this even in extreme heat)."
  50)

;; ----------------------------- site checks -----------------------------

(defn site-verified?
  "Ground-truth check: has `site`'s own record been marked verified
  (i.e. it has actually been surveyed, not merely referenced from an
  unverified route request)? A pure predicate over the site's own
  permanent field -- no proposal inspection needed."
  [site]
  (true? (:verified? site)))

(defn site-registered?
  "Ground-truth check: does `site`'s own record carry a `:registered?`
  true flag (i.e. it is on file in the operator's site registry)?
  Scheduling a pollination route against a site that is not on file
  and registered is the exact scope violation this actor's HARD
  invariant ('site/hive record must be independently verified/
  registered before any action') exists to block."
  [site]
  (true? (:registered? site)))

(defn site-ready?
  "Combined ground-truth gate: the site must be both `verified?` AND
  `registered?` before ANY pollination route may be scheduled against
  it."
  [site]
  (and (site-verified? site) (site-registered? site)))

;; ----------------------------- hive checks -----------------------------

(defn hive-verified?
  "Ground-truth check: has `hive`'s own record been marked verified
  (i.e. its queen-status/weight/temperature/acoustic-signal claims
  have actually been inspected, not merely logged from an unverified
  intake patch)?"
  [hive]
  (true? (:verified? hive)))

(defn hive-registered?
  "Ground-truth check: is `hive`'s own record on file in the
  operator's colony registry? Coordinating a harvest against a hive
  that is not on file and registered is the exact scope violation this
  actor's HARD invariant exists to block."
  [hive]
  (true? (:registered? hive)))

(defn hive-ready?
  "Combined ground-truth gate: the hive must be both `verified?` AND
  `registered?` before ANY harvest may be coordinated against it."
  [hive]
  (and (hive-verified? hive) (hive-registered? hive)))

(defn harvest-exceeds-sustainable-yield?
  "Ground-truth check for a `:coordinate-harvest` proposal: would
  `harvested-kg-to-date` + `new-kg` exceed `hive`'s own recorded
  `:max-sustainable-harvest-kg`? Needs no proposal inspection or
  stored-verdict lookup -- its inputs are permanent fields already on
  the hive's own record. Cumulative (not a single-harvest ceiling
  check) -- mirrors `motomfg.registry/shipment-quantity-exceeded?`'s
  cumulative-shipped-units pattern, deliberately richer than
  `cloud-itonami-isco-6123`'s single-harvest-vs-ceiling comparison
  (see this namespace's own docstring, Scope Note)."
  [hive new-kg]
  (let [ceiling (:max-sustainable-harvest-kg hive)
        so-far (:harvested-kg-to-date hive 0.0)]
    (and (number? ceiling)
         (number? new-kg)
         (number? so-far)
         ;; Compared at 1/10000 of a unit, not on raw doubles. A shipment
         ;; that fills a batch EXACTLY to its recorded capacity is legal,
         ;; and comparing the raw sum flagged such shipments as over
         ;; because the sum is not the double nearest the true total.
         (> (Math/round (* 10000 (+ (double so-far) (double new-kg))))
            (Math/round (* 10000 (double ceiling))))))) 

(defn harvest-exceeds-sustainable-yield-checkable?
  "Can `hive`'s headroom actually be computed for `new-kg`?

  `harvest-exceeds-sustainable-yield?` answers only `over` / `not over`, and its
  `(and (number? ...) ...)` guard made every un-checkable case fall
  through as `not over` -- a batch with no recorded capacity, or a
  shipment stating no amount, passed the over-capacity check silently.
  Callers must ask this first: un-checkable is not headroom."
  [hive new-kg]
  (boolean (and (map? hive)
                (number? (:max-sustainable-harvest-kg hive))
                (number? (:harvested-kg-to-date hive 0.0))
                (number? new-kg))))

(defn queen-status-valid?
  "Is `queen-status` one of the closed, known queen-status values?
  nil/blank is treated as invalid (a colony-health patch must declare
  a real queen status, not omit it silently)."
  [queen-status]
  (contains? valid-queen-statuses queen-status))

(defn weight-valid?
  "Is `weight-kg` a physically plausible total hive weight? Rejects
  nil, non-numbers, negative values, and values beyond
  `weight-max-kg`."
  [weight-kg]
  (and (number? weight-kg)
       (>= (double weight-kg) (double weight-min-kg))
       (<= (double weight-kg) (double weight-max-kg))))

(defn temperature-valid?
  "Is `temperature-c` a physically plausible hive-interior sensor
  reading? Rejects nil, non-numbers, and values outside the sensor
  plausibility band."
  [temperature-c]
  (and (number? temperature-c)
       (>= (double temperature-c) (double temperature-min-c))
       (<= (double temperature-c) (double temperature-max-c))))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human apiary inspector's/state animal-health authority's act,
  not this actor's. And NEVER a disease-free/quarantine-clearance
  certification -- this actor is never the notifiable-disease
  authority (see README `What this actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-route
  "Validate + construct the POLLINATION-ROUTE-SCHEDULE DRAFT -- a
  proposed pollination-route assignment against a verified, registered
  site. Pure function -- does not dispatch the hive-monitoring robot
  or move any bees; it builds the RECORD a coordinator would keep.
  `apiaryops.governor` independently re-verifies the site's own
  verified/registered ground truth, and permanently blocks any attempt
  to directly actuate a hive intervention (see README `Actuation`),
  before this is ever allowed to commit."
  [route-id site-id sequence]
  (when-not (and route-id (not= route-id ""))
    (throw (ex-info "route: route_id required" {})))
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "route: site_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "route: sequence must be >= 0" {})))
  (let [route-number (str "RTE-" (zero-pad sequence 6))
        record {"record_id" route-number
                "kind" "pollination-route-schedule-draft"
                "route_id" route-id
                "site_id" site-id
                "immutable" true}]
    {"record" record "route_number" route-number
     "certificate" (unsigned-certificate "PollinationRouteSchedule" route-number route-number)}))

(defn register-harvest
  "Validate + construct the HARVEST-COORDINATION DRAFT -- a proposed
  honey/wax harvest against a verified, registered hive. Pure function
  -- does not extract any honey; it builds the RECORD a coordinator
  would keep. `apiaryops.governor` independently re-verifies the
  harvest's own claimed quantity against
  `harvest-exceeds-sustainable-yield?`, before this is ever allowed to
  commit."
  [harvest-id sequence]
  (when-not (and harvest-id (not= harvest-id ""))
    (throw (ex-info "harvest: harvest_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "harvest: sequence must be >= 0" {})))
  (let [harvest-number (str "HRV-" (zero-pad sequence 6))
        record {"record_id" harvest-number
                "kind" "harvest-coordination-draft"
                "harvest_id" harvest-id
                "immutable" true}]
    {"record" record "harvest_number" harvest-number
     "certificate" (unsigned-certificate "HarvestCoordination" harvest-number harvest-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
