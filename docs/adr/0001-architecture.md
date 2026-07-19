# ADR-0001: ColonyAdvisor ⊣ Apiary Operations Governor architecture

## Status

Accepted. `cloud-itonami-unspsc-10` promoted from blueprint to
`:implemented`, following the verified fresh-scaffold protocol
established by prior actors in this fleet.

## Context

`cloud-itonami-unspsc-10` publishes an OSS blueprint for a managed
urban apiary and pollinator-services operator: colony-health data
logging (queen-status/weight/temperature/acoustic-signal), a
pollination-route scheduling service against customer sites,
disease/pest concern flagging, and honey/wax harvest coordination.
Like every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-3091` (Manufacture of
motorcycles, `motomfg`): both are back-office coordination actors with
a real physical/biological safety dimension, both share the same
four-op shape (`:log-X`/`:schedule-Y`/`:flag-safety-concern`/
`:coordinate-Z`), and both share the two-entity verified/registered
gate structure (one entity for the scheduling op, a second for the
coordination op). This build mirrors `motomfg`'s architecture closely
but adapts the hazard profile and vocabulary to apiary/pollinator
operations: this vertical's `sites` entity (a farm/garden/orchard/
campus/corridor a pollination route is scheduled against) plays
`motomfg.equipment`'s structural role, and `hives` (the colony record
a harvest is coordinated against, with a cumulative
`:harvested-kg-to-date` recomputed against a registered
`:max-sustainable-harvest-kg` ceiling) plays `motomfg.batches`'s role.

### Decision 0: Differentiation from `cloud-itonami-isco-6123`

`cloud-itonami-isco-6123` (ISCO-08 6123, Apiarists and Sericulturists)
already implements an `apiary.governor` named `ApiaryOperationsGovernor`
-- the SAME `:itonami.blueprint/governor` keyword this repo's own
`blueprint.edn` declares (a pre-existing fleet-wide overlap between the
occupation classification and this UNSPSC product/service
classification; both legitimately describe apiary work from different
classification axes, and this predates this build -- verified via
`grep -rl apiary-operations-governor` across `orgs/cloud-itonami`
before writing any code here).

The two are deliberately DIFFERENT, independent implementations, not a
shared codebase:

- `isco-6123`'s `apiary.governor` is a 4-file (`actor`/`advisor`/
  `governor`/`store`) individual-apiarist build: ONE op family
  (`:approve-harvest`/`:administer-hive-treatment`/`:approve-
  defensive-colony-operation`), a SINGLE-harvest-vs-ceiling arithmetic
  check (`harvest-kg > (:max-sustainable-harvest-kg h)`, no cumulative
  tracking), hive gated only by client-ownership (no `:verified?`/
  `:registered?` ground-truth pair), no site entity, no pollination-
  route service, no notifiable-disease-grounded concern flagging, and
  no phase-rollout gate.
- This repo (`unspsc-10`) is a 7-file (`advisor`/`governor`/
  `operation`/`phase`/`registry`/`store`/`sim`) managed-pollination-
  SERVICE-operator build: FOUR ops including a pollination-ROUTE
  scheduling service `isco-6123` has no equivalent of at all, a
  TWO-entity verified/registered gate (`sites` for routes, `hives` for
  harvests), a CUMULATIVE harvested-kg-to-date recompute (mirroring
  `motomfg.registry/shipment-quantity-exceeded?`'s pattern, richer
  than a single-value ceiling comparison), a WOAH (World Organisation
  for Animal Health, formerly OIE) Terrestrial Animal Health Code
  Chapter 9.2-grounded closed set of notifiable bee-disease concerns
  (verified 2026-07-19: American foulbrood is one of six bee diseases
  listed, notifiable in most WOAH member countries), a permanent
  hive-actuation block, a permanent certification-authority block, and
  the same Phase 0->3 rollout gate every richer sibling in this fleet
  uses.

This vertical has NO pre-existing `kotoba-lang/apiaryops`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`apiaryops.registry` (site/hive verification, cumulative harvest-
quantity recompute, queen-status validation, weight/temperature
plausibility validation) are re-verified independently by the
governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly `motomfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:apiary-operations-governor`, is NOT fleet-wide unique (see Decision 0
above) -- an intentional, documented exception to the usual
grep-verified-unique convention, because the overlap is a legitimate
same-domain naming coincidence across two different classification
axes, not a copy-paste error.

## Decision

### Decision 1: Self-contained domain logic (no external apiary-management capability library to wrap)

The site/hive-verification / cumulative-harvest-quantity / queen-
status / weight / temperature validation functions live as pure
functions in `apiaryops.registry` and are re-verified independently by
`apiaryops.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`motomfg.registry`).

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of apiary and
pollination-service operations. It does NOT:
- Open a hive, administer a treatment, or relocate a colony directly
- Make apiary-health or quarantine decisions (exclusive to the human apiary coordinator / state apiary inspector / animal-health authority)
- Actuate any hive intervention
- Self-issue a disease-free/quarantine-clearance certification

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human apiary-
coordinator approval. This is not a replacement for the coordinator's
authority or the animal-health authority's authority -- it is a
proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: apiary operations touch a real
biological-hazard domain (notifiable bee disease, colony-collapse
risk, food-safety-adjacent honey harvest). Disease/pest-concern
flagging NEVER auto-commits. All such concerns escalate immediately to
human review.

### Decision 3: Disease-concern escalation -- always human sign-off

`:flag-disease-concern` (suspected American/European foulbrood,
varroosis, small hive beetle, Tropilaelaps, nosemosis, or colony-
collapse signs -- drawn from `apiaryops.registry/valid-disease-
concerns`, WOAH Terrestrial Animal Health Code Chapter 9.2) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" --
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (site AND hive), not one

Like `motomfg`, this vertical has TWO entity kinds each gating a
different op: `:schedule-pollination-route` independently verifies the
referenced **site**'s own `:verified?`/`:registered?` fields;
`:coordinate-harvest` independently verifies the referenced **hive**'s
own `:verified?`/`:registered?` fields. Both are the same "site/hive
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-harvest` additionally independently
recomputes whether a hive's own recorded cumulative harvested-to-date
kg plus the proposal's own claimed kg would exceed the hive's own
recorded sustainable-harvest ceiling -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `apiaryops.governor`, mirroring `motomfg.governor`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Site/hive record (site for route scheduling, hive for harvest coordination) must be independently verified/registered before any action is taken against it, and a harvest's quantity must independently recompute within the hive's own logged sustainable ceiling
2. Proposals must be `:effect :propose` only (never direct hive-intervention actuation)
3. Direct hive-intervention actuation, or self-issued disease-free/quarantine-clearance certification, is permanently blocked
4. The op allowlist is closed -- `:log-colony-health`/`:schedule-pollination-route`/`:flag-disease-concern`/`:coordinate-harvest` only

## Consequences

(+) Apiary/pollination-service operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world hive intervention requires human
apiary-coordinator sign-off, and no disease-free/quarantine-clearance
certification can ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized hive intervention or certification self-issuance. Disease
concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: disease/pest-concern
flagging cannot be rate-limited, suppressed, or auto-decided by phase
gate. Human review is mandatory.

(-) Still a simulation/proposal layer, not a real apiary-management
control system. Hive intervention, quarantine decisions, and
certification issuance remain human-/institution-controlled via
external channels.

(-) No integration with real apiary-management databases (hive
telemetry, site scheduling, animal-health-authority APIs) -- this is a
standalone coordinator blueprint.

## Verification

- `cloud-itonami-unspsc-10`: `clojure -M:test` green (77 tests / 209
  assertions, 0 failures, 0 errors, verified from a fresh worktree
  checkout), demo narrative (`clojure -M:dev:run`) exercises proposal
  submission, escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, site-not-verified, hive-not-
  verified, harvest-exceeds-sustainable-yield, hive-actuate-blocked,
  certification-authority-blocked, already-scheduled, invalid-queen-
  status, invalid-weight, invalid-temperature), exit code 0.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
- WOAH Terrestrial Animal Health Code Chapter 9.2 (bee diseases) and
  American foulbrood's notifiable status were web-verified 2026-07-19
  against woah.org before being cited in `apiaryops.registry`'s
  `valid-disease-concerns` docstring -- no fabricated citation.
