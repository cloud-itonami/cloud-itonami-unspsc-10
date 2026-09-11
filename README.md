# cloud-itonami-unspsc-10

Open UNSPSC Blueprint (implemented actor) for **UNSPSC segment 10**:
Live Plant and Animal Material and Accessories and Supplies.

This repository publishes a forkable OSS business for an independent urban
apiary and pollinator-services operator: a hive-monitoring robot performs
colony health and pollination-route sensing under a governor-gated actor,
so a small apiary operator keeps auditable colony-health and
pollination-service records instead of renting a closed agtech SaaS.

Built on this workspace's `langgraph-clj` StateGraph runtime -- the
same actor pattern as [`cloud-itonami-isic-3091`](https://github.com/cloud-itonami/cloud-itonami-isic-3091)
(MotoAdvisor ⊣ Motorcycle Plant Operations Governor, whose structure
this actor ports). Here it is **ColonyAdvisor ⊣ Apiary Operations
Governor**.

`cloud-itonami-isco-6123` (Apiarists and Sericulturists, occupation
classification) independently implements a DIFFERENT, simpler actor
that happens to share the same `:apiary-operations-governor` blueprint
keyword (a pre-existing fleet-wide naming overlap, documented and not
a shared codebase) -- see
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md)
Decision 0 for the full differentiation record.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a hive-monitoring robot (weight,
temperature, acoustic swarm-signal sensing; pollination-route mapping)
performs the on-site monitoring under an actor that proposes a
colony-health assessment and an independent **Apiary Operations Governor**
that gates interventions. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions (e.g. suspected disease requiring
quarantine, or treatment near a food-producing hive) require human
sign-off.

## Core Contract

```text
hive/site registry + prior colony-health history
        |
        v
Colony Advisor -> Apiary Operations Governor -> intervention, or human sign-off
        |
        v
robot monitoring actions (gated) + colony-health record + audit ledger
```

No automated assessment can dispatch a robot action the governor
refuses, suppress a colony-health record, or recommend a treatment
without governor approval and audit evidence.

## Implementation

Portable `.cljc` namespaces under `src/apiaryops/`:

- `registry` -- pure domain logic: site/hive verified+registered
  checks, cumulative harvest-quantity recompute, queen-status/weight/
  temperature plausibility validation, draft route-schedule/harvest-
  coordination record construction.
- `store` -- SSoT behind a `Store` protocol (`MemStore`); hives,
  sites, routes, harvests, disease concerns and the audit ledger all
  live here.
- `advisor` -- the contained intelligence node (`mock-advisor` default,
  `llm-advisor` swap-in); returns proposals only.
- `governor` -- the independent Apiary Operations Governor (twelve
  concrete checks, four HARD invariants).
- `phase` -- 0->3 staged rollout; pollination-route scheduling is
  never auto-committed at any phase.
- `operation` -- the StateGraph (1 run = 1 coordination request);
  `sim` drives the demo.

`kbb -M:test` (77 tests, 209 assertions, 0 failures). See
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for
the full design, including the documented `cloud-itonami-isco-6123`
governor-keyword-overlap differentiation and the WOAH Terrestrial
Animal Health Code citation backing `flag-disease-concern`.

## Capability layer

Resolves via [`kotoba-lang/unspsc`](https://github.com/kotoba-lang/unspsc)
(UNSPSC segment `10`). Required capabilities:

- :robotics
- :telemetry
- :optimization
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
