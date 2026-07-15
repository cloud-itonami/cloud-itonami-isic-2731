# cloud-itonami-isic-2731: Manufacture of fibre optic cables

Open Business Blueprint for **ISIC 2731**: manufacture of fibre optic cables — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **fibre-optic-cable-plant operations**: production-batch data logging (product-type/attenuation-dB/km/quantity/defect-rate), drawing/coating/cabling-line-equipment maintenance scheduling, safety-concern flagging, and outbound cable-reel shipment coordination.

This repository designs a forkable OSS business for fibre-optic-cable-
plant operations: run by a qualified operator so a plant keeps its
own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not drawing/cabling-line control

ISIC 2731 is the class for manufacture of fibre optic cables — pulling glass fibre from a heated preform on a draw tower (with in-line primary/secondary UV-cured acrylate coating), then cabling (stranding coated fibres into buffer tubes or ribbons and jacketing/extruding the finished cable). This is distinct from sibling ISIC 2732 ("Manufacture of other electronic and electric wires and cables"), which covers copper-conductor wire/cable products rather than optical fibre, and from ISIC 2733 ("Manufacture of wiring devices" — switches, socket-outlets, plugs, junction boxes), which covers fixed circuit-terminating devices rather than cable itself. The manufacturing plant runs a fibre-draw tower (drawing + coating) and a cabling line (stranding + jacketing), and runs end-of-line optical test (OTDR/cutback attenuation measurement) before shipment. This actor coordinates the back-office record keeping around that plant — it never touches the drawing/coating/cabling-line equipment directly, and it is never a fibre-optic-cable compliance certification authority (e.g. Telcordia GR-20, ITU-T G.65x, UL/NFPA fire-rating marks).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — drawing/coating/cabling batch, output-quality (attenuation-dB/km test) data logging (administrative, not an operational decision)
- `:schedule-maintenance` — drawing/coating/cabling-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/quality-defect concern (always escalates)
- `:coordinate-shipment` — outbound cable-reel shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(drawing/coating/cabling-line equipment, fibre-optic-cable compliance
certification, downstream product-safety and worker-safety
consequence):

- Does NOT control drawing, coating, or cabling-line equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate drawing/coating/cabling-line equipment (human plant supervisor decides)
- Does NOT self-issue a fibre-optic-cable compliance certification mark (e.g. Telcordia GR-20, ITU-T G.65x, UL/NFPA fire-rating — the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`fibreopticmfg.operation/build`, a langgraph-clj StateGraph):
1. **`fibreopticmfg.advisor`** (sealed intelligence node, `FibreOpticCableAdvisor`): proposes decisions only, never commits
2. **`fibreopticmfg.governor`** (independent, `Fibre Optic Cable Plant Operations Governor`): validates against domain rules, re-derived from `fibreopticmfg.registry`'s pure functions and `fibreopticmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct drawing/coating/cabling-line-equipment control)
     - Directly actuating drawing/coating/cabling-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a fibre-optic-cable compliance certification mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:attenuation-db-km` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`fibreopticmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`fibreopticmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
