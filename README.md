# cloud-itonami-unspsc-10

Open UNSPSC Blueprint for **UNSPSC segment 10**: Live Plant and Animal
Material and Accessories and Supplies.

This repository designs a forkable OSS business for an independent urban
apiary and pollinator-services operator: a hive-monitoring robot performs
colony health and pollination-route sensing under a governor-gated actor,
so a small apiary operator keeps auditable colony-health and
pollination-service records instead of renting a closed agtech SaaS.

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

No automated assessment can dispatch a robot action the governor refuses,
suppress a colony-health record, or recommend a treatment without governor
approval and audit evidence.

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
