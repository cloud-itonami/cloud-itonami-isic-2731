(ns fibreopticmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  demo page and no generator. Nothing on the generated page is typed by
  hand. `run-demo!` drives the REAL actor stack --
  `fibreopticmfg.operation` (langgraph-clj StateGraph) ->
  `fibreopticmfg.governor` -> `fibreopticmfg.store` -- and `render`
  then reads back ONLY:

    - store entities: `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`, `store/safety-concerns`
    - the append-only audit ledger: `store/ledger`
    - `fibreopticmfg.registry`'s own pure ground-truth predicates
      (`batch-ready?` / `equipment-ready?`), which are the same
      functions the governor itself re-derives its HARD verdicts from

  The single exception is `action-gate-rows` below: a static
  description of this actor's own fixed op/gate contract
  (`fibreopticmfg.governor/allowed-ops` + `fibreopticmfg.phase/phases`).
  That is documentation-of-code, not runtime telemetry, and is
  commented as such at its definition.

  INPUT PROVENANCE. Every plant entity this scenario touches is a
  SEEDED id from `fibreopticmfg.store/sample-data!` -- `batch-001`,
  `batch-002`, `batch-003`, `draw-tower-001`, `cabling-line-002`. No id
  is invented. The `mnt-*` / `ship-*` / `concern-*` values passed as
  `:subject` are NOT plant entities and are not expected to pre-exist:
  in this domain a maintenance window, a shipment coordination and a
  safety concern are DRAFTS this run creates (`store/commit-record!`'s
  `:maintenance/schedule` / `:shipment/propose` /
  `:safety-concern/flag` branches key the new record by the request's
  own subject, and `fibreopticmfg.registry` issues its
  `MNT-`/`SHP-`-numbered immutable draft record). That is the repo's
  own contract -- `fibreopticmfg.sim` does exactly the same -- so the
  drafts on this page are produced by the run, never asserted by it.

  SCENARIO (see `run-demo!` for the per-op detail). One clean, fully
  approved lifecycle on the verified/registered `batch-001` +
  `draw-tower-001` pair; one human REJECTION; and eleven HARD holds
  covering all twelve of `fibreopticmfg.governor`'s HARD rules. Both
  PERMANENT blocks (direct drawing/coating/cabling-line equipment
  actuation, and self-issuing a fibre-optic-cable compliance
  certification mark) are exercised, and neither ever reaches a human.

  DETERMINISM. The advisor is the deterministic `mock-advisor`, the
  store is a fresh `mem-store` per run, all sequence numbers restart at
  zero, and no clock or random source is read -- two consecutive runs
  are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [fibreopticmfg.operation :as op]
            [fibreopticmfg.registry :as registry]
            [fibreopticmfg.store :as store]))

(def ^:private coordinator
  "The operating context every request in this scenario runs under.
  Phase 3 (`fibreopticmfg.phase/default-phase`) is the most permissive
  rollout phase this actor has, so every escalation and hold below is
  the actor's PERMANENT posture, not a phase-rollout artefact."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store (`store/mem-store` + `store/sample-data!`)
  through a scenario reaching every disposition this actor can produce.
  Returns the store.

  Clean lifecycle -- `batch-001` (verified, registered, 500.0 units
  logged / 100.0 already shipped) and `draw-tower-001` (verified,
  registered):
    1. `:log-production-batch` on `batch-001` -- the ONE op phase 3
       allows to auto-commit when the governor is clean. No human.
    2. `:schedule-maintenance` `mnt-2731-1` against `draw-tower-001` --
       escalates (never auto at any phase), approved, committed.
    3. `:flag-safety-concern` `concern-2731-1` against `draw-tower-001`
       -- ALWAYS escalates on `:coordination/safety-concern` stake,
       approved, committed.
    4. `:coordinate-shipment` `ship-2731-1` against `batch-001` (50.0
       units, within the batch's own logged quantity) -- escalates,
       approved, committed.

  Human rejection -- the other side of the approval gate:
    5. `:coordinate-shipment` `ship-2731-2` against `batch-001` (200.0
       units, still within quantity after step 4) -- escalates, and the
       human shipping approver REJECTS. Ledger fact `:approval-rejected`,
       no SSoT mutation.

  HARD holds -- one request per rule, exercising the failure mode
  directly rather than only via a happy path (all twelve governor
  rules; none reaches a human):
    6.  `:equipment-not-verified`         -- maintenance against the
                                             UNVERIFIED/unregistered
                                             `cabling-line-002`
    7.  `:batch-not-verified`             -- shipment against the
                                             UNVERIFIED/unregistered
                                             `batch-003`
    8.  `:shipment-quantity-exceeded`     -- `batch-002` has 200.0 units
                                             logged and 180.0 already
                                             shipped; a 100.0-unit
                                             shipment blows through it
    9.  `:equipment-actuate-blocked`      -- PERMANENT: a maintenance
                                             proposal that declares
                                             `:actuate-equipment? true`
    10. `:certification-authority-blocked`-- PERMANENT: a batch patch
                                             that declares
                                             `:issue-certification? true`
    11. `:already-scheduled`              -- `mnt-2731-1` a second time
    12. `:invalid-product-type`           -- a fabricated product type
    13. `:invalid-attenuation-db-km`      -- an implausible OTDR/cutback
                                             attenuation reading
    14. `:invalid-defect-rate`            -- an implausible defect rate
    15. `:not-propose-effect`             -- a mis-wired caller whose own
                                             request `:effect` is not
                                             `:propose`
    16. `:unknown-op` + `:equipment-control-blocked` -- an op outside the
                                             closed four-op allowlist,
                                             whose proposal effect is
                                             therefore also outside the
                                             closed effect allowlist"
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; 1. clean production-batch log -> phase-3 auto-commit, no human.
    (exec! actor "clean-batch-log"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :loose-tube
                    :attenuation-db-km 0.34
                    :defect-rate-percent 0.7
                    :last-assessed "2026-07-14"}})

    ;; 2. maintenance window on a verified/registered draw tower.
    (exec! actor "clean-maintenance"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2731-1"
            :value {:equipment-id "draw-tower-001"
                    :maintenance-type :coating-die-inspection
                    :scheduled-date "2026-08-01"
                    :actuate-equipment? false}})
    (approve! actor "clean-maintenance")

    ;; 3. safety concern -- always escalates regardless of confidence.
    (exec! actor "clean-safety-concern"
           {:op :flag-safety-concern :effect :propose :subject "concern-2731-1"
            :value {:equipment-id "draw-tower-001" :severity :moderate
                    :description "コーティング炉の温度異常兆候、線引き張力の変動"}})
    (approve! actor "clean-safety-concern")

    ;; 4. shipment within the batch's own logged production quantity.
    (exec! actor "clean-shipment"
           {:op :coordinate-shipment :effect :propose :subject "ship-2731-1"
            :value {:batch-id "batch-001" :units 50.0
                    :destination "buyer-yard-north"}})
    (approve! actor "clean-shipment")

    ;; 5. same shape, but the human shipping approver rejects.
    (exec! actor "rejected-shipment"
           {:op :coordinate-shipment :effect :propose :subject "ship-2731-2"
            :value {:batch-id "batch-001" :units 200.0
                    :destination "buyer-yard-west"}})
    (reject! actor "rejected-shipment")

    ;; 6-16. HARD holds, one request per rule.
    (exec! actor "hold-equipment-not-verified"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2731-2"
            :value {:equipment-id "cabling-line-002" :maintenance-type :calibration
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "hold-batch-not-verified"
           {:op :coordinate-shipment :effect :propose :subject "ship-2731-3"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-yard-south"}})

    (exec! actor "hold-quantity-exceeded"
           {:op :coordinate-shipment :effect :propose :subject "ship-2731-4"
            :value {:batch-id "batch-002" :units 100.0
                    :destination "buyer-yard-east"}})

    (exec! actor "hold-actuate-blocked"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2731-3"
            :value {:equipment-id "draw-tower-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! actor "hold-certification-blocked"
           {:op :log-production-batch :effect :propose :subject "batch-002"
            :patch {:issue-certification? true}})

    (exec! actor "hold-already-scheduled"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2731-1"
            :value {:equipment-id "draw-tower-001"
                    :maintenance-type :coating-die-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "hold-invalid-product-type"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:product-type :unobtainium}})

    (exec! actor "hold-invalid-attenuation"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:attenuation-db-km 999.0}})

    (exec! actor "hold-invalid-defect-rate"
           {:op :log-production-batch :effect :propose :subject "batch-002"
            :patch {:defect-rate-percent 999.0}})

    (exec! actor "hold-not-propose-effect"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-type :loose-tube}})

    (exec! actor "hold-unknown-op"
           {:op :actuate-draw-tower :effect :propose :subject "batch-003"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- cell
  "Render one store-entity value. `nil` is shown as an em dash rather
  than the empty string so a missing field is visibly missing."
  [v]
  (cond
    (nil? v) "<span class=\"muted\">—</span>"
    (true? v) "<span class=\"ok\">yes</span>"
    (false? v) "<span class=\"critical\">no</span>"
    (keyword? v) (str "<code>" (esc (name v)) "</code>")
    (number? v) (str "<span class=\"num\">" (esc v) "</span>")
    :else (esc v)))

(defn- basis-str [basis]
  (->> basis
       (map #(if (keyword? %) (name %) (str %)))
       (str/join ", ")))

;; `fibreopticmfg.operation` appends exactly three fact types to the
;; ledger: `:committed` (the :commit node), and `:governor-hold` /
;; `:approval-rejected` (the :hold node, which filters the audit channel
;; for those two `:t` values). `:approval-granted` and
;; `:approval-requested` are written to the in-memory `:audit` channel
;; ONLY and never reach `store/ledger`, so nothing below branches on
;; them -- a branch on those would be permanently unreachable.
(def ^:private ledger-fact-types #{:committed :governor-hold :approval-rejected})

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell
  "Last ledger disposition recorded against `subject-id`."
  [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (case (:t f)
      :committed "<span class=\"ok\">committed</span>"
      :governor-hold
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (basis-str (:basis f))) "</span>")
      :approval-rejected
      (str "<span class=\"warn\">approval rejected &middot; "
           (esc (basis-str (:basis f))) "</span>")
      "<span class=\"muted\">no ledger activity</span>")))

(defn- batch-row [ledger {:keys [id product-type spool-length-km attenuation-db-km
                                 quantity-units shipped-units defect-rate-percent
                                 last-assessed] :as b}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (cell product-type) (cell spool-length-km) (cell attenuation-db-km)
          (cell quantity-units) (cell shipped-units) (cell defect-rate-percent)
          (cell last-assessed)
          (if (registry/batch-ready? b)
            "<span class=\"ok\">verified + registered</span>"
            "<span class=\"critical\">not verified/registered</span>")
          (status-cell ledger id)))

(defn- equipment-row [maintenance {:keys [id kind last-maintenance-date
                                          last-scheduled-maintenance-date] :as eq}]
  (let [windows (filter #(= id (:equipment-id %)) maintenance)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td>%s</td><td>%s</td></tr>")
            (esc id) (cell kind) (cell last-maintenance-date)
            (cell last-scheduled-maintenance-date)
            (cell (count windows))
            (if (registry/equipment-ready? eq)
              "<span class=\"ok\">verified + registered</span>"
              "<span class=\"critical\">not verified/registered</span>"))))

(defn- maintenance-row [ledger {:keys [id equipment-id maintenance-type scheduled-date
                                       actuate-equipment? scheduled? maintenance-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc equipment-id) (cell maintenance-type) (cell scheduled-date)
          (cell actuate-equipment?) (cell scheduled?) (cell maintenance-number)
          (status-cell ledger id)))

(defn- shipment-row [ledger {:keys [id batch-id units destination shipment-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc batch-id) (cell units) (cell destination)
          (cell shipment-number)
          (status-cell ledger id)))

(defn- concern-row [ledger {:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc equipment-id) (cell severity) (esc description)
          (status-cell ledger id)))

(defn- detail-str
  "The fact's own explanation: the governor's per-violation `:detail`
  strings for a hold, the advisor's committed `:summary` otherwise."
  [{:keys [t summary violations basis]}]
  (if (= :committed t)
    (str summary)
    (let [ds (keep :detail violations)]
      (if (seq ds) (str/join " / " ds) (basis-str basis)))))

(defn- ledger-row [i {:keys [t op subject disposition basis] :as f}]
  (format (str "        <tr><td class=\"num\">%s</td><td>%s</td><td><code>%s</code></td>"
               "<td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (inc i)
          (case t
            :committed "<span class=\"ok\">committed</span>"
            :governor-hold "<span class=\"critical\">governor-hold</span>"
            :approval-rejected "<span class=\"warn\">approval-rejected</span>"
            (esc (name t)))
          (esc (name op)) (esc subject)
          (esc (name (or disposition :n-a)))
          (esc (basis-str basis))
          (esc (detail-str f))))

(def ^:private action-gate-rows
  ;; STATIC documentation of this actor's own fixed contract --
  ;; `fibreopticmfg.governor/allowed-ops` (the closed four-op allowlist)
  ;; crossed with `fibreopticmfg.phase/phases` (only
  ;; `:log-production-batch` is ever a member of a phase `:auto` set,
  ;; including phase 3). This is documentation-of-code, NOT runtime
  ;; telemetry: it describes behaviour that is the same on every run,
  ;; so it is legitimately hand-described here. Every other table on
  ;; the page is read back from the store/ledger after a real run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean &middot; the only auto-eligible op</span></td><td>product-type / attenuation-dB/km / defect-rate independently range-checked; certification self-issue permanently blocked</td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never in any phase's <code>:auto</code> set</span></td><td>equipment re-derived <code>:verified?</code> AND <code>:registered?</code>; double-schedule blocked; direct equipment actuation permanently blocked</td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; <code>:coordination/safety-concern</code> stake, no confidence threshold</span></td><td>never gated on the referenced equipment being verified — a concern may be raised about anything</td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never in any phase's <code>:auto</code> set</span></td><td>batch re-derived <code>:verified?</code> AND <code>:registered?</code>; shipped-units + claim independently recomputed against the batch's own logged quantity</td></tr>"
   "        <tr><td colspan=\"3\"><span class=\"muted\">Any op outside this closed allowlist, any proposal effect outside <code>#{:batch/upsert :maintenance/schedule :safety-concern/flag :shipment/propose}</code>, and any request whose own <code>:effect</code> is not <code>:propose</code> are HARD holds.</span></td></tr>"])

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows)
         (str (str/join "\n" rows) "\n")
         (str "        <tr><td colspan=\"" (count headers)
              "\"><span class=\"muted\">no rows</span></td></tr>\n"))
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders operator-console.html from a store `db` that has already been
  driven by `run-demo!` (or any other real scenario). Reads store
  entities + the append-only ledger only."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        maintenance (store/all-maintenance db)
        concerns (vec (store/safety-concerns db))
        ;; The Store protocol exposes `shipment` by id but no
        ;; `all-shipments`, so the shipment set is derived from the ids
        ;; this run actually committed (ledger order) and each row's
        ;; fields are then read from the shipment's own store entity.
        shipments (->> ledger
                       (filter #(and (= :committed (:t %))
                                     (= :coordinate-shipment (:op %))))
                       (map :subject)
                       distinct
                       (keep #(store/shipment db %)))
        holds (filter #(= :governor-hold (:t %)) ledger)]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-2731 &middot; Operator Console</title>\n<style>\n"
     (jp-go-dds.skin/dds+skin)
     "\n</style></head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of fibre optic cables (ISIC 2731) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance / safety-concern / shipment always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>About this page</h2>\n"
     "    <p>Generated at build time by <code>clojure -M:dev:render-html</code> (<code>fibreopticmfg.render-html</code>) by actually running the actor — <code>fibreopticmfg.operation</code> (a langgraph-clj StateGraph) → <code>fibreopticmfg.governor</code> → <code>fibreopticmfg.store</code> — against this repo's own seeded plant data (<code>fibreopticmfg.store/sample-data!</code>: batches <code>batch-001</code>/<code>batch-002</code>/<code>batch-003</code>, equipment <code>draw-tower-001</code>/<code>cabling-line-002</code>). Every id, status, hold reason and ledger row below is read back from the store and the append-only audit ledger after that run. Nothing is hand-typed except the op/gate contract table, which documents fixed code.</p>\n"
     "    <p class=\"muted\">This run: <span class=\"num\">" (count ledger) "</span> ledger facts · "
     "<span class=\"num\">" (count holds) "</span> HARD holds · "
     "<span class=\"num\">" (count maintenance) "</span> maintenance window(s) scheduled · "
     "<span class=\"num\">" (count shipments) "</span> shipment(s) coordinated · "
     "<span class=\"num\">" (count concerns) "</span> safety concern(s) flagged.</p>\n"
     "  </section>\n"

     (section "Production batches"
              "Fields are the batch's own stored record. <em>Ground truth</em> is <code>fibreopticmfg.registry/batch-ready?</code> — the same predicate the governor re-derives independently, never the advisor's self-report."
              ["Batch" "Product type" "Spool length (km)" "Attenuation (dB/km)"
               "Quantity (units)" "Shipped (units)" "Defect rate (%)" "Last assessed"
               "Ground truth" "Last ledger status"]
              (map (partial batch-row ledger) batches))

     (section "Drawing / coating / cabling-line equipment"
              "This actor never touches the equipment — it only drafts maintenance windows against it. <em>Ground truth</em> is <code>fibreopticmfg.registry/equipment-ready?</code>."
              ["Equipment" "Kind" "Last maintenance" "Last scheduled maintenance"
               "Windows scheduled this run" "Ground truth"]
              (map (partial equipment-row maintenance) equipment))

     (section "Maintenance windows (drafts)"
              "Draft windows created by committed <code>:maintenance/schedule</code> records. <code>maintenance-number</code> is issued by <code>fibreopticmfg.registry/register-maintenance</code>. A draft is never an actuation."
              ["Window" "Equipment" "Type" "Scheduled date" "Actuate equipment?"
               "Scheduled?" "Registry number" "Ledger status"]
              (map (partial maintenance-row ledger) maintenance))

     (section "Shipment coordination (drafts)"
              "Draft outbound cable-reel shipments created by committed <code>:shipment/propose</code> records. <code>shipment-number</code> is issued by <code>fibreopticmfg.registry/register-shipment</code>."
              ["Shipment" "Batch" "Units" "Destination" "Registry number" "Ledger status"]
              (map (partial shipment-row ledger) shipments))

     (section "Safety concerns"
              "Appended to the store's own safety-concern log. <code>:flag-safety-concern</code> always escalates to a human, at every phase, regardless of confidence."
              ["Concern" "Equipment" "Severity" "Description" "Ledger status"]
              (map (partial concern-row ledger) concerns))

     (section "Action gate (Fibre Optic Cable Plant Operations Governor)"
              "HARD holds cannot be overridden by any phase or any human. Direct drawing/coating/cabling-line equipment actuation and self-issuing a fibre-optic-cable compliance certification mark (Telcordia GR-20 / ITU-T G.65x / UL/NFPA fire-rating) are PERMANENT blocks — they never reach an approver."
              ["Op" "Gate" "Independently re-derived checks"]
              action-gate-rows)

     (section "Audit ledger (this run)"
              (str "The append-only decision log, in order. Only three fact types reach it: "
                   "<code>:committed</code>, <code>:governor-hold</code> and <code>:approval-rejected</code> "
                   "— <code>:approval-granted</code>/<code>:approval-requested</code> go to the in-memory audit "
                   "channel only. <em>Basis</em> is the advisor's cited fields on a commit, and the violated "
                   "governor rules on a hold.")
              ["#" "Fact" "Op" "Subject" "Disposition" "Basis" "Detail"]
              (map-indexed ledger-row ledger))

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-2731 — Manufacture of fibre optic cables. "
     "Regenerate with <code>clojure -M:dev:render-html</code>. "
     "Styling: <code>jp-go-dds.skin/dds+skin</code> (デジタル庁デザインシステム).</p>\n"
     "</footer>\n"
     "</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        ledger (store/ledger db)
        html (render db)]
    (when-let [parent (.getParentFile (java.io.File. ^String out))]
      (.mkdirs parent))
    (spit out html)
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count (filter #(= :governor-hold (:t %)) ledger)) " HARD holds, "
                  (count (remove ledger-fact-types (map :t ledger))) " unexpected fact types, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts)"))))
