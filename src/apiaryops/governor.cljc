(ns apiaryops.governor
  "Apiary Operations Governor -- the independent compliance layer that
  earns the ColonyAdvisor the right to commit. The advisor has no
  notion of whether a site it wants to schedule a pollination route
  against has actually been surveyed/registered, whether a hive it
  wants to coordinate a harvest against has actually been inspected/
  registered, whether a route proposal secretly tries to ACTUATE
  (rather than merely draft-schedule) a hive intervention, whether a
  proposal secretly tries to self-issue a disease-free/quarantine-
  clearance CERTIFICATION (an authority this actor never holds),
  whether a harvest proposal's own claimed quantity would blow through
  the hive's own logged sustainable-harvest ceiling, or when an act
  stops being a coordination proposal and becomes direct hive
  intervention, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:apiary-operations-governor` (see
  docs/adr/0001-architecture.md -- this keyword is a verified existing
  overlap with `cloud-itonami-isco-6123`'s own governor of the same
  name; see that ADR's Decision 1 for the differentiation record. The
  two governors are independent code, not a shared implementation).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:hive/open` or
                                       `:treatment/administer`) is the
                                       'direct hive-intervention
                                       actuation' scope violation this
                                       actor must NEVER perform -- HARD,
                                       PERMANENT, unconditional.
    4. Hive-actuate blocked        -- for `:schedule-pollination-route`,
                                       does the proposal's own `:value`
                                       declare `:actuate-hive? true`?
                                       Directly opening a hive,
                                       administering a treatment, or
                                       relocating a colony is this
                                       actor's other permanent scope
                                       boundary (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `apiaryops.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Certification authority
       blocked                     -- ANY proposal (any op) whose own
                                       `:value`/`:patch` declares
                                       `:issue-certification? true` is
                                       attempting to self-issue a
                                       disease-free/quarantine-clearance
                                       certification -- an authority
                                       exclusively reserved to the state
                                       apiary inspector / animal-health
                                       authority (WOAH notifiable-
                                       disease chain), never this actor
                                       -- HARD, PERMANENT, unconditional.
    6. Site not verified/
       registered                  -- for `:schedule-pollination-route`,
                                       INDEPENDENTLY verify the
                                       referenced site's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`apiaryops.registry/site-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status.
    7. Already scheduled           -- for `:schedule-pollination-route`,
                                       refuses to schedule the SAME
                                       route record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    8. Hive not verified/
       registered                  -- for `:coordinate-harvest`,
                                       INDEPENDENTLY verify the
                                       referenced hive's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`apiaryops.registry/hive-
                                       ready?`) -- never trust the
                                       advisor's own rationale.
    9. Harvest exceeds
       sustainable yield           -- for `:coordinate-harvest`,
                                       INDEPENDENTLY recompute whether
                                       the hive's own recorded
                                       `:harvested-kg-to-date` plus the
                                       proposal's own claimed `:kg`
                                       would exceed the hive's own
                                       recorded
                                       `:max-sustainable-harvest-kg`
                                       (`apiaryops.registry/harvest-
                                       exceeds-sustainable-yield?`) --
                                       ground truth from the hive's own
                                       permanent fields, never a
                                       self-reported quantity claim.
   10. Invalid queen-status        -- for `:log-colony-health`, if the
                                       patch declares a `:queen-status`
                                       outside the closed known set
                                       (`apiaryops.registry/queen-
                                       status-valid?`), the hive record
                                       is rejected rather than let a
                                       fabricated status through.
   11. Invalid weight              -- for `:log-colony-health`, if the
                                       patch declares a `:weight-kg`
                                       that is not a physically
                                       plausible reading
                                       (`apiaryops.registry/weight-
                                       valid?`), the hive record is
                                       rejected rather than let a
                                       fabricated/sensor-error reading
                                       through.
   12. Invalid temperature         -- for `:log-colony-health`, if the
                                       patch declares a
                                       `:temperature-c` that is not a
                                       physically plausible reading
                                       (`apiaryops.registry/
                                       temperature-valid?`), the hive
                                       record is rejected rather than
                                       let fabricated/sensor-error data
                                       through.
   13. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/disease-
                                       concern`, ALWAYS set for
                                       `:flag-disease-concern`) --
                                       escalate to a human apiary
                                       inspector. SOFT: the human may
                                       approve."
  (:require [apiaryops.registry :as registry]
            [apiaryops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-colony-health :schedule-pollination-route
    :flag-disease-concern :coordinate-harvest})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct hive-
  intervention-actuation effect."
  #{:hive/upsert :route/schedule
    :disease-concern/flag :harvest/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Disease concerns are the one op in this domain that always demands
  human eyes regardless of confidence."
  #{:coordination/disease-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- hive-intervention-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct hive-intervention actuation, a fabricated
  treatment effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :hive-intervention-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は巣箱への直接介入に該当する可能性があり、恒久的に禁止")}]))

(defn- hive-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-pollination-route`
  proposal whose own `:value` declares `:actuate-hive? true` is
  attempting to directly open a hive, administer a treatment, or
  relocate a colony -- this actor may only ever propose/schedule a
  DRAFT pollination route, never actuate a hive intervention directly.
  No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-pollination-route)
             (true? (:actuate-hive? (:value proposal))))
    [{:rule :hive-actuate-blocked
      :detail "巣箱への直接介入(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- certification-authority-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal (any op) whose own
  `:value`/`:patch` declares `:issue-certification? true` is attempting
  to self-issue a disease-free/quarantine-clearance certification -- an
  authority exclusively reserved to the state apiary inspector /
  animal-health authority (WOAH notifiable-disease chain), never this
  actor. No phase and no human approval can ever override this."
  [proposal]
  (let [payload (or (:value proposal) (:patch proposal))]
    (when (true? (:issue-certification? payload))
      [{:rule :certification-authority-blocked
        :detail "疾病無し証明・検疫解除証明の自己発行提案は恒久的に禁止 -- 家畜衛生当局の専権事項"}])))

(defn- site-not-verified-violations
  "For `:schedule-pollination-route`, INDEPENDENTLY verify the
  referenced site exists and is both `:verified?` AND `:registered?`
  -- never trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (= op :schedule-pollination-route)
    (let [site-id (:site-id (:value proposal))
          s (and site-id (store/site st site-id))]
      (when-not (and s (registry/site-ready? s))
        [{:rule :site-not-verified
          :detail (str site-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みサイト記録が無い状態での送粉ルート予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-pollination-route`, refuses to schedule the SAME
  route record twice, off a dedicated `:scheduled?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-pollination-route)
    (when (store/route-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- hive-not-verified-violations
  "For `:coordinate-harvest`, INDEPENDENTLY verify the referenced hive
  exists and is both `:verified?` AND `:registered?` -- never trust
  the advisor's own report."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-harvest)
    (let [hive-id (:hive-id (:value proposal))
          h (and hive-id (store/hive st hive-id))]
      (when-not (and h (registry/hive-ready? h))
        [{:rule :hive-not-verified
          :detail (str hive-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み巣箱記録が無い状態での採蜜調整提案")}]))))

(defn- harvest-exceeds-sustainable-yield-violations
  "For `:coordinate-harvest`, INDEPENDENTLY recompute whether the
  hive's own recorded cumulative-harvested-to-date quantity plus the
  proposal's own claimed quantity would exceed the hive's own recorded
  `:max-sustainable-harvest-kg` -- ground truth from the hive's own
  permanent fields, never a self-reported quantity claim."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-harvest)
    (let [{:keys [hive-id kg]} (:value proposal)
          h (and hive-id (store/hive st hive-id))]
      (when (and h (registry/harvest-exceeds-sustainable-yield? h kg))
        [{:rule :harvest-exceeds-sustainable-yield
          :detail (str hive-id " の登録済み持続可能上限(" (:max-sustainable-harvest-kg h)
                       "kg)を、既存採蜜実績(" (:harvested-kg-to-date h 0.0)
                       "kg)+今回申請(" kg "kg)が超過")}]))))

(defn- invalid-queen-status-violations
  "For `:log-colony-health`, if the patch declares a `:queen-status`
  outside the closed known set, reject rather than let a fabricated
  status through."
  [{:keys [op]} proposal]
  (when (= op :log-colony-health)
    (let [queen-status (:queen-status (:value proposal))]
      (when (and (some? queen-status) (not (registry/queen-status-valid? queen-status)))
        [{:rule :invalid-queen-status
          :detail (str queen-status " は既知の queen-status 値ではない")}]))))

(defn- invalid-weight-violations
  "For `:log-colony-health`, if the patch declares a `:weight-kg` that
  is not a physically plausible reading, reject rather than let
  fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-colony-health)
    (let [w (:weight-kg (:value proposal))]
      (when (and (some? w) (not (registry/weight-valid? w)))
        [{:rule :invalid-weight
          :detail (str w "kg は物理的に妥当な hive weight の範囲外")}]))))

(defn- invalid-temperature-violations
  "For `:log-colony-health`, if the patch declares a `:temperature-c`
  that is not a physically plausible reading, reject rather than let
  fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-colony-health)
    (let [tc (:temperature-c (:value proposal))]
      (when (and (some? tc) (not (registry/temperature-valid? tc)))
        [{:rule :invalid-temperature
          :detail (str tc "°C は物理的に妥当な hive-interior 温度の範囲外")}]))))

(defn check
  "Censors a ColonyAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (hive-intervention-blocked-violations proposal)
                           (hive-actuate-blocked-violations request proposal)
                           (certification-authority-blocked-violations proposal)
                           (site-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (hive-not-verified-violations request proposal st)
                           (harvest-exceeds-sustainable-yield-violations request proposal st)
                           (invalid-queen-status-violations request proposal)
                           (invalid-weight-violations request proposal)
                           (invalid-temperature-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
