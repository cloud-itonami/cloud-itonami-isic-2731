(ns fibreopticmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL actor stack -- `fibreopticmfg.store/mem-store` +
  `sample-data!` (the seed), `fibreopticmfg.operation/build` (the
  langgraph-clj StateGraph: intake -> advise -> govern -> decide ->
  commit | request-approval | hold) and `fibreopticmfg.governor` (the
  independent censor) -- and renders what actually came back. There is
  no mock ledger, no hand-typed hold string, no fabricated field: every
  row below is read out of the store the graph just wrote, and every
  HARD-hold rule name / detail string in the page is the governor's own
  `:basis` / `:violations` output.

  Provenance discipline (deliberate, and checked by eye against
  `fibreopticmfg.store/sample-batches` + `sample-equipment` before this
  file was written):

    - every `batch-*` / `*-tower-*` / `cabling-line-*` id this demo
      drives already exists in the seed;
    - every `mnt-*` / `ship-*` / `concern-*` id is a DRAFT id created by
      this demo's own registration op (`:maintenance/schedule`,
      `:shipment/propose`, `:safety-concern/flag`) -- the drafts that
      HARD-hold are, correctly, never created, which is exactly what a
      hold means;
    - the only ledger fact types branched on are the three this store
      actually appends -- `:committed` (from `:commit`) and
      `:governor-hold` / `:approval-rejected` (from `:hold`).
      `:approval-granted` and `:approval-requested` are emitted to the
      in-memory `:audit` channel ONLY and never reach
      `store/append-ledger!`, so no status branch may depend on them.

  Determinism: the advisor is the deterministic `mock-advisor`, the
  store is an atom of EDN, `all-batches`/`all-equipment`/`all-maintenance`
  sort by `:id`, and nothing here reads a clock. Two consecutive runs
  produce byte-identical output.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [fibreopticmfg.governor :as governor]
            [fibreopticmfg.operation :as op]
            [fibreopticmfg.phase :as phase]
            [fibreopticmfg.store :as store]))

;; ----------------------------- driving the real actor -----------------------------

(def ^:private coordinator
  "The same operator context `fibreopticmfg.sim` uses -- phase 3
  (supervised-auto), so the phase gate is at its most permissive and
  every hold below is the governor's own doing, not a rollout artefact."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec!
  "One coordination request = one graph run."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- resume!
  "Resume an `interrupt-before :request-approval` pause with a human
  plant supervisor's / shipping approver's decision."
  [actor tid status]
  (g/run* actor {:approval {:status status :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Seeds a fresh store and walks it through every disposition this actor
  can reach, then through all twelve of `fibreopticmfg.governor`'s HARD
  rules. Returns the store.

  Clean paths (the seed's verified + registered entities):
    t1  batch-001 production-batch log -- governor-clean, high
        confidence, and `:log-production-batch` is the ONE member of
        phase 3's `:auto` set, so it auto-commits with no human.
    t2  mnt-1 maintenance window against draw-tower-001 -- governor-clean
        but `:schedule-maintenance` is deliberately absent from EVERY
        phase's `:auto` set, so it escalates; a human approves.
    t3  concern-1 safety concern on draw-tower-001 -- ALWAYS escalates
        (`:coordination/safety-concern` is in `governor/high-stakes`);
        a human approves.
    t4  ship-1, 50.0 reels off batch-001 (500.0 produced, 100.0 already
        shipped) -- within the batch's own recorded quantity, escalates,
        a human approves, and the batch's `:shipped-units` moves to
        150.0.
    t5  ship-2, 25.0 more reels off batch-001 -- also clean, also
        escalates, and here the human REJECTS. The store records
        `:approval-rejected` and nothing mutates: the veto is as
        auditable as the approval.

  HARD holds (one request per rule, each exercised directly rather than
  only via a happy path -- the discipline this fleet's siblings
  establish). Every one of these is refused BEFORE any human sees it:
    t6  :equipment-not-verified        -- mnt-2 against cabling-line-002,
                                          which the seed records as
                                          UNVERIFIED + unregistered.
    t7  :batch-not-verified            -- ship-3 against batch-003, which
                                          the seed records as UNVERIFIED
                                          + unregistered.
    t8  :shipment-quantity-exceeded    -- ship-4 asks for 100.0 reels off
                                          batch-002, whose own record says
                                          200.0 produced and 180.0 already
                                          shipped. Recomputed from the
                                          batch's own fields, never from
                                          the proposal's claim.
    t9  :equipment-actuate-blocked     -- mnt-3 asks to ACTUATE the draw
                                          tower rather than draft a
                                          window. Permanent; no phase and
                                          no approver can override it.
    t10 :already-scheduled             -- mnt-1 a second time, off the
                                          dedicated `:scheduled?` fact.
    t11 :invalid-product-type          -- a fabricated `:unobtainium`
                                          product type.
    t12 :invalid-attenuation-db-km     -- 250.0 dB/km, far outside any
                                          real OTDR/cutback reading.
    t13 :invalid-defect-rate           -- 140.0%, a batch cannot reject
                                          more than 100% of its output.
    t14 :certification-authority-blocked -- a patch trying to self-issue a
                                          fibre-optic-cable compliance
                                          certification mark. Permanent.
    t15 :not-propose-effect            -- a mis-wired caller whose request
                                          declares `:effect :direct-write`.
                                          Checked before anything else.
    t16 :unknown-op + :equipment-control-blocked
                                       -- an op outside the closed
                                          allowlist, whose proposal effect
                                          is likewise outside the closed
                                          propose-shaped effect allowlist."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean paths -------------------------------------------------
    (exec! actor "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                       :patch {:product-type :loose-tube :last-assessed "2026-07-14"}})

    (exec! actor "t2" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "draw-tower-001"
                               :maintenance-type :coating-die-inspection
                               :scheduled-date "2026-08-01"
                               :actuate-equipment? false}})
    (resume! actor "t2" :approved)

    (exec! actor "t3" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "draw-tower-001" :severity :moderate
                               :description "コーティング炉の温度異常兆候、線引き張力の変動"}})
    (resume! actor "t3" :approved)

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                       :value {:batch-id "batch-001" :units 50.0
                               :destination "buyer-yard-north"}})
    (resume! actor "t4" :approved)

    (exec! actor "t5" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                       :value {:batch-id "batch-001" :units 25.0
                               :destination "buyer-yard-west"}})
    (resume! actor "t5" :rejected)

    ;; --- HARD holds --------------------------------------------------
    (exec! actor "t6" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                       :value {:equipment-id "cabling-line-002"
                               :maintenance-type :calibration
                               :scheduled-date "2026-08-05"
                               :actuate-equipment? false}})

    (exec! actor "t7" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                       :value {:batch-id "batch-003" :units 100.0
                               :destination "buyer-yard-south"}})

    (exec! actor "t8" {:op :coordinate-shipment :effect :propose :subject "ship-4"
                       :value {:batch-id "batch-002" :units 100.0
                               :destination "buyer-yard-east"}})

    (exec! actor "t9" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                       :value {:equipment-id "draw-tower-001"
                               :maintenance-type :force-run
                               :scheduled-date "2026-09-01"
                               :actuate-equipment? true}})

    (exec! actor "t10" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "draw-tower-001"
                                :maintenance-type :coating-die-inspection
                                :scheduled-date "2026-08-01"
                                :actuate-equipment? false}})

    (exec! actor "t11" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:product-type :unobtainium}})

    (exec! actor "t12" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:attenuation-db-km 250.0}})

    (exec! actor "t13" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:defect-rate-percent 140.0}})

    (exec! actor "t14" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:issue-certification? true}})

    (exec! actor "t15" {:op :log-production-batch :effect :direct-write :subject "batch-001"
                        :patch {:product-type :loose-tube}})

    (exec! actor "t16" {:op :actuate-cabling-line :effect :propose :subject "cabling-line-002"})

    db))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))
(defn- code [v] (str "<code>" (esc v) "</code>"))
(defn- n-cell [v] (str "<span class=\"num\">" (esc v) "</span>"))
(defn- dash [v] (if (nil? v) "<span class=\"muted\">—</span>" (esc v)))

(defn- yes-no [v]
  (if (true? v)
    "<span class=\"ok\">yes</span>"
    "<span class=\"critical\">no</span>"))

(defn- rules-of
  "The governor's own rule names for one hold fact."
  [fact]
  (mapv kw (:basis fact)))

(defn- details-of
  "The governor's own violation detail strings for one hold fact.
  `keep`, not `map`: the `:approver-rejected` pseudo-violation the
  `:request-approval` node synthesises carries a `:rule` and no
  `:detail`, and a column of blanks is worse than an honest dash."
  [fact]
  (into [] (keep :detail) (:violations fact)))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell
  "Last thing the ledger says about `subject`. Only the three fact types
  `fibreopticmfg.store` actually appends are branched on here."
  [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      :committed          "<span class=\"ok\">committed</span>"
      :governor-hold      (str "<span class=\"critical\">HARD hold</span> "
                               (str/join " " (map code (rules-of f))))
      :approval-rejected  (str "<span class=\"warn\">approver rejected</span> "
                               (str/join " " (map code (rules-of f))))
      "<span class=\"muted\">no activity</span>")))

(defn- table [headers rows]
  (if (seq rows)
    (str "<table><thead><tr>"
         (str/join (map #(str "<th>" (esc %) "</th>") headers))
         "</tr></thead><tbody>\n"
         (str/join "\n" (map (fn [cells]
                               (str "<tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))
                             rows))
         "\n</tbody></table>")
    "<p class=\"muted\">(none)</p>"))

;; ----------------------------- sections -----------------------------

(defn- batches-section [db ledger]
  (str "<section class=\"card\"><h2>生産バッチ / Production batches</h2>"
       "<p class=\"muted\">Seeded by <code>fibreopticmfg.store/sample-data!</code>; "
       "<code>:shipped-units</code> and <code>:last-assessed</code> below are whatever the "
       "committed ops actually left behind.</p>"
       (table ["Batch" "Product type" "Spool km" "Attenuation dB/km" "Produced (reels)"
               "Shipped (reels)" "Defect %" "QC verified?" "Registered?" "Last assessed" "Last ledger fact"]
              (for [b (store/all-batches db)]
                [(code (:id b)) (code (kw (:product-type b)))
                 (n-cell (:spool-length-km b)) (n-cell (:attenuation-db-km b))
                 (n-cell (:quantity-units b)) (n-cell (:shipped-units b))
                 (n-cell (:defect-rate-percent b))
                 (yes-no (:verified? b)) (yes-no (:registered? b))
                 (dash (:last-assessed b))
                 (status-cell ledger (:id b))]))
       "</section>"))

(defn- equipment-section [db ledger]
  (str "<section class=\"card\"><h2>設備 / Plant equipment</h2>"
       "<p class=\"muted\">A maintenance window may only ever be drafted against equipment whose "
       "own record is both <code>:verified?</code> and <code>:registered?</code> "
       "(<code>fibreopticmfg.registry/equipment-ready?</code>), re-derived by the governor and never "
       "taken from the advisor's rationale.</p>"
       (table ["Equipment" "Kind" "Inspected?" "Registered?" "Last maintenance" "Last scheduled" "Last ledger fact"]
              (for [e (store/all-equipment db)]
                [(code (:id e)) (code (kw (:kind e)))
                 (yes-no (:verified? e)) (yes-no (:registered? e))
                 (dash (:last-maintenance-date e))
                 (dash (:last-scheduled-maintenance-date e))
                 (status-cell ledger (:id e))]))
       "</section>"))

(defn- gate-section []
  (let [{:keys [label writes auto]} (get phase/phases (:phase coordinator))]
    (str "<section class=\"card\"><h2>Action gate — phase "
         (:phase coordinator) " <span class=\"badge\">" (esc label) "</span></h2>"
         "<p class=\"muted\">Read straight out of <code>fibreopticmfg.phase/phases</code> and "
         "<code>fibreopticmfg.governor/allowed-ops</code>. Confidence floor "
         "<code>" (esc governor/confidence-floor) "</code>; stakes that always demand a human: "
         (str/join ", " (map (comp code str) (sort-by str governor/high-stakes)))
         ".</p>"
         (table ["Op" "Writes at this phase?" "Gate"]
                (for [o (sort-by name governor/allowed-ops)]
                  [(code o)
                   (yes-no (contains? writes o))
                   (cond
                     (not (contains? writes o))
                     "<span class=\"critical\">phase-disabled — HOLD</span>"
                     (contains? auto o)
                     "<span class=\"ok\">auto-commit when governor-clean</span>"
                     :else
                     "<span class=\"warn\">ALWAYS human approval</span>")]))
         "<p class=\"muted\">A governor HOLD always stays a HOLD — the phase gate can only ever "
         "make this table stricter, never looser.</p></section>")))

(defn- holds-section [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)]
    (str "<section class=\"card\"><h2>Governor HARD holds</h2>"
         "<p class=\"muted\">Refused before any human was asked. Rule names and detail text below are "
         "the governor's own <code>:basis</code> / <code>:violations</code> output for these runs — "
         "nothing here is written by the renderer.</p>"
         (table ["Rule" "Op" "Subject" "Confidence" "Governor detail"]
                (for [f holds
                      :let [rs (rules-of f) ds (details-of f)]]
                  [(str/join " " (map #(str "<span class=\"critical\">" (code %) "</span>") rs))
                   (code (kw (:op f)))
                   (code (:subject f))
                   (n-cell (:confidence f))
                   (str/join "<br>" (map esc ds))]))
         "<p class=\"muted\">" (esc (count holds)) " HARD holds, firing "
         (esc (count (into #{} (mapcat rules-of holds))))
         " distinct governor rules.</p></section>")))

(defn- maintenance-section [db]
  (str "<section class=\"card\"><h2>保守作業予定ドラフト / Maintenance-schedule drafts</h2>"
       "<p class=\"muted\">Built by <code>fibreopticmfg.registry/register-maintenance</code> — a DRAFT "
       "record, never an actuation of the drawing/coating/cabling line.</p>"
       (table ["Maintenance" "Equipment" "Type" "Scheduled date" "Record no." "Scheduled?" "Actuate requested?"]
              (for [m (store/all-maintenance db)]
                [(code (:id m)) (code (:equipment-id m)) (code (kw (:maintenance-type m)))
                 (dash (:scheduled-date m)) (code (:maintenance-number m))
                 (yes-no (:scheduled? m))
                 (if (true? (:actuate-equipment? m))
                   "<span class=\"critical\">yes</span>"
                   "<span class=\"muted\">no</span>")]))
       (table ["Registry record id" "Kind" "Maintenance id" "Equipment id" "Immutable"]
              (for [r (store/maintenance-history db)]
                [(code (get r "record_id")) (esc (get r "kind"))
                 (code (get r "maintenance_id")) (code (get r "equipment_id"))
                 (yes-no (get r "immutable"))]))
       "</section>"))

(defn- shipments-section [db]
  (str "<section class=\"card\"><h2>出荷調整ドラフト / Shipment-coordination drafts</h2>"
       "<p class=\"muted\">Built by <code>fibreopticmfg.registry/register-shipment</code> — a DRAFT "
       "record, never a real freight dispatch. Each row is looked up through "
       "<code>fibreopticmfg.store/shipment</code> off the shipment id in the registry history.</p>"
       (table ["Shipment" "Batch" "Reels" "Destination" "Record no."]
              (for [r (store/shipment-history db)
                    :let [s (store/shipment db (get r "shipment_id"))]]
                [(code (:id s)) (code (:batch-id s)) (n-cell (:units s))
                 (dash (:destination s)) (code (:shipment-number s))]))
       "<p class=\"muted\">Who approved a draft is deliberately NOT a column here: the "
       "<code>:request-approval</code> node puts <code>:approved-by</code> on the record's "
       "<code>:payload</code>, but <code>fibreopticmfg.store/commit-record!</code> persists "
       "<code>:value</code> — so no such field exists on a stored shipment. The approval itself is in "
       "the audit ledger below.</p>"
       "</section>"))

(defn- concerns-section [db]
  (str "<section class=\"card\"><h2>安全懸念 / Safety concerns</h2>"
       "<p class=\"muted\">Never gated on the referenced equipment being verified — a concern may be "
       "raised about any equipment. Always high-stakes, so always a human's call "
       "(<code>fibreopticmfg.governor/high-stakes</code>).</p>"
       (table ["Concern" "Equipment" "Severity" "Description"]
              (for [c (store/safety-concerns db)]
                [(code (:id c)) (code (:equipment-id c)) (code (kw (:severity c)))
                 (esc (:description c))]))
       "</section>"))

(defn- ledger-section [ledger]
  (str "<section class=\"card\"><h2>監査台帳 / Audit ledger</h2>"
       "<p class=\"muted\">Append-only, in commit order. These are the only three fact types "
       "<code>fibreopticmfg.store/append-ledger!</code> ever receives: <code>:committed</code> from the "
       "<code>:commit</code> node, and <code>:governor-hold</code> / <code>:approval-rejected</code> from "
       "the <code>:hold</code> node. <code>:approval-granted</code> and <code>:approval-requested</code> "
       "live in the graph's in-memory <code>:audit</code> channel only and are deliberately absent.</p>"
       (table ["#" "Fact" "Op" "Subject" "Disposition" "Basis" "Summary / governor detail"]
              (map-indexed
               (fn [i f]
                 [(n-cell (inc i))
                  (case (:t f)
                    :committed         "<span class=\"ok\">:committed</span>"
                    :governor-hold     "<span class=\"critical\">:governor-hold</span>"
                    :approval-rejected "<span class=\"warn\">:approval-rejected</span>"
                    (code (kw (:t f))))
                  (code (kw (:op f)))
                  (code (:subject f))
                  (code (kw (:disposition f)))
                  (str/join " " (map code (rules-of f)))
                  (cond
                    (seq (details-of f)) (str/join "<br>" (map esc (details-of f)))
                    (:summary f)         (esc (:summary f))
                    :else                (dash nil))])
               ledger))
       "</section>"))

;; ----------------------------- page -----------------------------

(defn render
  "The whole console, from a store the real actor has already written."
  [db]
  (let [ledger (vec (store/ledger db))]
    (str "<!DOCTYPE html>\n<html lang=\"ja\">\n<head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
         "<meta name=\"color-scheme\" content=\"light\">"
         "<title>Operator console — 光ファイバケーブル製造 (ISIC 2731) | cloud-itonami-isic-2731</title>"
         "<meta name=\"description\" content=\"Build-time operator console generated by driving the real fibreopticmfg governed actor.\">"
         "<style>" (skin/dds+skin) "</style></head>\n<body>\n"
         "<div class=\"bar\"><h1>Operator console — 光ファイバケーブル製造工場</h1>"
         "<span class=\"badge\">ISIC 2731</span></div>"
         "<p class=\"subtitle\">Generated by <code>clojure -M:dev:render-html</code> "
         "(<code>fibreopticmfg.render-html</code>) by running the real "
         "<code>fibreopticmfg.operation</code> StateGraph against a freshly seeded "
         "<code>fibreopticmfg.store</code>. Every value on this page came back out of that store; "
         "the renderer invents nothing and contains no timestamp, so re-running it is "
         "byte-identical.</p>"
         "<div class=\"note\"><p><strong>What this actor never does.</strong> It never actuates the "
         "fibre-draw tower, coating line or cabling line; it never dispatches a real freight carrier; "
         "and it never issues a fibre-optic-cable compliance-certification mark (Telcordia GR-20, "
         "ITU-T G.65x, UL/NFPA fire ratings) — that authority belongs to the accredited certification "
         "body. Attempts at the last two appear as permanent HARD holds below.</p></div>"
         (batches-section db ledger)
         (equipment-section db ledger)
         (gate-section)
         (holds-section ledger)
         (maintenance-section db)
         (shipments-section db)
         (concerns-section db)
         (ledger-section ledger)
         "<footer><p>cloud-itonami-isic-2731 — governed open occupation blueprint. "
         "Styling: <code>jp-go-digital-design-system</code> (デジタル庁デザインシステム) via "
         "<code>jp-go-dds.skin/dds+skin</code>. Regenerate with "
         "<code>clojure -M:dev:render-html</code>.</p></footer>\n</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        f (java.io.File. ^String out)]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f (render db))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count (filter #(= :governor-hold (:t %)) (store/ledger db)))
                  " HARD holds)"))))
