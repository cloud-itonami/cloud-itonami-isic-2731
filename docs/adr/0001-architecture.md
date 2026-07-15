# ADR-0001: FibreOpticCableAdvisor ⊣ Fibre Optic Cable Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2731` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2731` publishes an OSS blueprint for the ISIC 2731
class ("Manufacture of fibre optic cables") **plant operations
coordination** (production-batch product-type/attenuation-dB/km/
quantity/defect-rate data logging, drawing/coating/cabling-line-
equipment maintenance scheduling, safety-concern flagging, and
outbound fibre-optic-cable-reel shipment coordination). Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

The closest domain analogs are `cloud-itonami-isic-2732` (Manufacture
of other electronic and electric wires and cables) and
`cloud-itonami-isic-2733` (Manufacture of wiring devices): all three
are ISIC division-27 electrical/electronic-cable-and-device
back-office coordination actors for a fixed manufacturing PLANT with
heavy line equipment and a real physical safety dimension, and all
three share the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure (equipment
for maintenance scheduling, batch for shipment coordination). This
build mirrors `cloud-itonami-isic-2733`'s architecture most closely
(its own closest analogs were `cloud-itonami-isic-2790` and
`cloud-itonami-isic-2710`) but adapts the product/hazard vocabulary to
ISIC 2731's own scope: fibre drawing (pulling fibre from a heated
glass preform on a draw tower, with in-line primary/secondary
UV-cured acrylate coating) followed by cabling (stranding coated
fibres into buffer tubes or ribbons and jacketing/extruding the
finished cable) -- distinct from sibling ISIC 2732 (copper-conductor
wire/cable products, not optical fibre) and ISIC 2733 (fixed
circuit-terminating devices -- switches, socket-outlets, plugs,
junction boxes -- not cable itself). This vertical's production-batch
record declares a `:product-type` (closed set spanning loose-tube/
ribbon/tight-buffered/adss cable-construction families per Telcordia
GR-20 / ITU-T L.10 generic cable-construction categories) and an
`:attenuation-db-km` (a routine OTDR/cutback attenuation test reading
in dB/km, plausibility-checked 0-50 dB/km against the working range of
standard production optical test methods per IEC 60793-1-40
acceptance testing) in addition to a `:defect-rate-percent`, rather
than 2733's `:contact-resistance-milliohm` (a micro-ohmmeter contact-
resistance reading on a switching/terminal contact) -- ISIC 2731's
fibre-optic cable is typically QC-tested with an OTDR/cutback
attenuation measurement on the finished fibre itself, reflecting this
domain's focus on optical transmission quality rather than electrical
contact/terminal integrity. Its shipment quantity is tracked in
finished cable-reel UNITS (`:units`/`:quantity-units`/
`:shipped-units`), the same shape 2733 uses for finished units
(counted, not weighed, for freight coordination).

Like both siblings, this vertical is subject to compliance
certification regimes (e.g. Telcordia GR-20 generic requirements,
ITU-T G.65x series fibre/cable specifications, UL 1666/NFPA 262
plenum/riser fire-rating marks) for its finished fibre-optic-cable
products. This actor is never the certification authority -- any
proposal (regardless of op) that declares `:issue-certification? true`
is a HARD, PERMANENT, unconditional block
(`fibreopticmfg.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the
equipment-actuation block.

This vertical has NO pre-existing `kotoba-lang/fibreopticmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`fibreopticmfg.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, attenuation-dB/km
plausibility validation, defect-rate plausibility validation) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2733`'s `wiringdevmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:fibre-optic-cable-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "fibre-optic-cable-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created). The
`fibreopticmfg` namespace prefix was likewise verified UNIQUE fleet-wide
before use (`gh search code "fibreopticmfg" --owner cloud-itonami`, zero
hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external fibre-optic-cable-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
ISIC 2731 vertical has NO pre-existing capability library to wrap. The
equipment/batch-verification / shipment-quantity / product-type /
attenuation-dB/km / defect-rate validation functions live as pure
functions in `fibreopticmfg.registry` and are re-verified independently
by `fibreopticmfg.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-2733`'s `wiringdevmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of ISIC 2731
fibre-optic-cable plant operations. It does NOT:
- Control drawing, coating, or cabling-line equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate drawing/coating/cabling-line equipment
- Self-issue a fibre-optic-cable compliance certification mark (e.g. Telcordia GR-20, ITU-T G.65x, UL/NFPA fire-rating)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: fibre-optic-cable manufacturing (glass
fibre drawing, high-temperature coating ovens, cabling-line stranding/
jacketing) is a safety-critical domain (fibre-breakage hazards,
coating-oven thermal hazards, downstream network-infrastructure-
reliability and worker-safety consequence). Safety-concern flagging
NEVER auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (fibre-breakage-hazard concern, coating-oven-
thermal-hazard concern, equipment-safety concern) ALWAYS escalates,
never auto-commits. This is not a "low-stakes proposal" — it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like both siblings, this vertical has TWO entity kinds each gating a
different op: `:schedule-maintenance` independently verifies the
referenced **equipment** unit's own `:verified?`/`:registered?`
fields; `:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit quantity plus the proposal's own claimed unit quantity would
exceed the batch's own recorded production quantity — never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `fibreopticmfg.governor`, mirroring `cloud-itonami-isic-2733`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct drawing/coating/cabling-line-equipment control, equipment actuation, or self-issued fibre-optic-cable compliance certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) ISIC 2731 fibre-optic-cable plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no certification mark can ever be
self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2731`: `clojure -M:test` green (see the
  superproject ADR and `kotoba-lang/industry` registry entry for the
  exact fresh-clone re-verified output), `clojure -M:lint` clean,
  `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  certification-authority-blocked, already-scheduled, invalid-
  product-type, invalid-attenuation-db-km, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
