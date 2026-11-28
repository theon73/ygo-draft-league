import { useState, type ReactNode } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import { apiPost, apiPut, type DraftState, type PoolSummary, type SeasonRules } from "../../api/client";
import { Loading } from "../../components/Feedback";
import PlayerAvatar from "../../components/PlayerAvatar";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { DraftSettings } from "./SeasonDraftPage";
import SeasonRulesPage from "./SeasonRulesPage";
import { useSeason } from "./SeasonLayout";
import SeasonSetup from "./SeasonSetup";

// The season setup wizard (YGO-32): one directed route through setting up a season, after which
// editing stays non-linear on the regular tabs. Each step is the real editor (the same forms as the
// Rules, Players and Draft tabs), and saving moves on, so leaving halfway is safe: the season
// remembers the step (setupStep) and the Overview offers "Continue setup".
//
// The seat draw isn't a step: it's its own moment on the Overview, once the players are in.

export const SETUP_STEPS = [
  { n: 1, title: "Calendar", what: "When the season starts." },
  { n: 2, title: "Players", what: "Who plays, and who helps run it." },
  { n: 3, title: "Card pool", what: "Which cards are in this format." },
  { n: 4, title: "Draft", what: "How the cards get shared out." },
  { n: 5, title: "Season", what: "Matches, trades and playoffs." },
  { n: 6, title: "Review", what: "Check it over and finish." },
] as const;

export default function SetupWizard() {
  const { league } = useLeague();
  const { season, reload } = useSeason();
  const navigate = useNavigate();
  const step = Math.min(6, Math.max(1, Number(useParams().step) || 1));
  const base = `/leagues/${league.id}/seasons/${season.id}`;

  if (season.status !== "SETUP" || !season.canManage) return <Navigate to={`${base}/overview`} replace />;

  /** Remember the step (so Continue setup resumes there) and open it. */
  const go = async (n: number) => {
    await apiPut(`${base}/setup-step`, { step: n });
    reload();
    navigate(`${base}/setup/${n}`);
    window.scrollTo(0, 0);
  };
  const next = () => void go(step + 1);
  const back = step > 1 ? <button type="button" className="ghost" onClick={() => void go(step - 1)}>Back</button> : null;
  const info = SETUP_STEPS[step - 1];

  return (
    <div className="wizard">
      <ol className="wizard-steps" aria-label="Setup steps">
        {SETUP_STEPS.map((s) => (
          <li key={s.n} className={s.n === step ? "current" : s.n < step ? "done" : undefined}>
            <button type="button" onClick={() => void go(s.n)} aria-current={s.n === step ? "step" : undefined}>
              <span className="step-no">{s.n < step ? "✓" : s.n}</span>
              <span className="step-title">{s.title}</span>
            </button>
          </li>
        ))}
      </ol>

      <div className="wizard-head">
        <h3>{info.n}. {info.title}</h3>
        <p className="muted">{info.what}</p>
      </div>

      {step === 1 && <div className="single-section"><SeasonRulesPage sections={["calendar"]} saveLabel="Save and continue" onSaved={next} extraFooter={back} /></div>}
      {step === 2 && <PlayersStep footer={<WizardFooter back={back} next={next} />} />}
      {step === 3 && <PoolStep base={base} back={back} next={next} />}
      {step === 4 && <DraftStep base={base} back={back} next={next} />}
      {step === 5 && (
        <SeasonRulesPage sections={["competition", "transactions", "results", "playoffs"]} saveLabel="Save and continue" onSaved={next} extraFooter={back} />
      )}
      {step === 6 && <ReviewStep base={base} back={back} go={(n) => void go(n)} />}

      <p className="wizard-note">
        Nothing here is final: everything can be changed after setup from the Rules, Players, Pool and Draft tabs, until the draft
        starts. <Link to={`${base}/overview`}>Leave setup for now</Link> and come back from the Overview.
      </p>
    </div>
  );
}

function WizardFooter({ back, next, label = "Continue", disabled = false, quiet = false }: {
  back: ReactNode; next: () => void; label?: string; disabled?: boolean; quiet?: boolean;
}) {
  return (
    <p className="form-actions">
      {back}
      <button type="button" className={quiet ? "ghost push-right" : "primary"} onClick={next} disabled={disabled}>{label}</button>
    </p>
  );
}

// ---- 2. Players -----------------------------------------------------------------------------

function PlayersStep({ footer }: { footer: ReactNode }) {
  const { league, reload: reloadLeague } = useLeague();
  const { season, reload } = useSeason();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const owner = league.myRole === "OWNER";

  // Someone who isn't in the league yet: a placeholder seat now, a claim link for them later.
  const addPlaceholder = async () => {
    setError(null);
    try {
      await apiPost(`/leagues/${league.id}/members/placeholders`, { displayName: name });
      setName("");
      reloadLeague();
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <SeasonSetup wizard />
      {owner && (
        <div className="panel">
          <h5>Someone missing?</h5>
          <p className="muted small">
            Add them as a placeholder to set up the season now, then send them a claim link from <Link to={`/leagues/${league.id}/members`}>Members</Link>.
          </p>
          <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void addPlaceholder(); }}>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Their name" maxLength={40} />
            <button type="submit" disabled={!name.trim()}>Add</button>
          </form>
          {error && <p className="bad">{error}</p>}
        </div>
      )}
      {season.participants.length < 2 && <p className="muted small">A season needs at least two players.</p>}
      {footer}
    </>
  );
}

// ---- 3. Card pool ---------------------------------------------------------------------------

function PoolStep({ base, back, next }: { base: string; back: ReactNode; next: () => void }) {
  const pool = useApi<PoolSummary>(`${base}/pool`);
  const [building, setBuilding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Saving the cutoff and ban list builds (or rebuilds) the pool from them.
  const build = async () => {
    setBuilding(true);
    setError(null);
    try {
      await apiPost(`${base}/pool/generate`);
      pool.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBuilding(false);
    }
  };
  const p = pool.data;

  return (
    <>
      <div className="single-section"><SeasonRulesPage sections={["format"]} saveLabel={p && p.total > 0 ? "Save and rebuild the pool" : "Save and build the pool"}
                       onSaved={() => void build()} /></div>
      {building && <Loading what="the pool" />}
      {error && <p className="bad">{error}</p>}
      {p && p.total > 0 && (
        <div className="panel pool-result">
          <strong>{p.total.toLocaleString()} cards in the pool</strong>
          <span className="muted">
            {p.generatedCutoff && ` · released by ${p.generatedCutoff}`}
            {p.exceptions > 0 && ` · ${p.exceptions} exception${p.exceptions === 1 ? "" : "s"}`}
            {p.banList && ` · ${p.banList}`}
          </span>
          <p className="muted small">
            Fine-tune it (add exceptions, ban or limit cards) any time in the <Link to={`${base}/pool`}>pool browser</Link>.
          </p>
        </div>
      )}
      <WizardFooter back={back} next={next} label={p && p.total > 0 ? "Continue" : "Skip for now"} quiet={!p || p.total === 0} />
    </>
  );
}

// ---- 4. Draft -------------------------------------------------------------------------------

function DraftStep({ base, back, next }: { base: string; back: ReactNode; next: () => void }) {
  const draft = useApi<DraftState>(`${base}/draft`);
  if (!draft.data) return <Loading />;
  const d = draft.data;
  return (
    <>
      <p className="muted small">
        {d.draftableCards === 0 ? "Build the card pool (step 3) to see how many cards there are to draft."
          : <>{d.draftableCards.toLocaleString()} draftable cards for {d.seats.length} player{d.seats.length === 1 ? "" : "s"}
            {d.seats.length > 0 && `: up to ${Math.floor(d.draftableCards / d.seats.length)} picks each`}.</>}
      </p>
      <DraftSettings key={JSON.stringify(d.rounds)} draft={d} base={`${base}/draft`} onSaved={next} saveLabel="Save and continue" extraFooter={back} />
    </>
  );
}

// ---- 6. Review ------------------------------------------------------------------------------

function ReviewStep({ base, back, go }: { base: string; back: ReactNode; go: (n: number) => void }) {
  const { season, reload } = useSeason();
  const navigate = useNavigate();
  const rules = useApi<SeasonRules>(`${base}/rules`);
  const draft = useApi<DraftState>(`${base}/draft`);
  const pool = useApi<PoolSummary>(`${base}/pool`);
  if (!rules.data || !draft.data || !pool.data) return <Loading />;
  const r = rules.data;
  const d = draft.data;

  const finish = async () => {
    await apiPut(`${base}/setup-step`, { step: null });
    reload();
    navigate(`${base}/overview`);
  };
  const card = (n: number, title: string, body: ReactNode) => (
    <section className="review-card">
      <div className="review-head"><h5>{title}</h5><button type="button" className="link" onClick={() => go(n)}>Edit</button></div>
      {body}
    </section>
  );
  const ready = d.readiness.every((c) => c.ok);

  return (
    <>
      <div className="review-grid">
        {card(1, "Calendar", <p>{r.calendar.startDate ? `Starts ${r.calendar.startDate}` : "No start date yet"}{r.weeks.length > 0 && ` · ${r.weeks.length} weeks`}</p>)}
        {card(2, "Players", season.participants.length === 0 ? <p className="muted">Nobody yet</p> : (
          <ul className="player-chips small">
            {season.participants.map((p) => (
              <li key={p.memberId}><PlayerAvatar name={p.displayName} passcode={p.avatar} size="sm" />{p.displayName}</li>
            ))}
          </ul>
        ))}
        {card(3, "Card pool", <p>
          {pool.data.total > 0 ? `${pool.data.total.toLocaleString()} cards` : "Not built yet"}
          {r.format.tcgCutoff && ` · up to ${r.format.tcgCutoff}`} · {r.format.banList?.name ?? "no ban list"}
        </p>)}
        {card(4, "Draft", <p>
          {d.order === "SNAKE" ? "Snake" : "Linear"} · {d.roundCount ? `${d.roundCount} rounds` : "rounds not set"}
          {d.timer ? ` · ${Math.round(d.timer.seconds / 60) || d.timer.seconds + "s"}${d.timer.seconds >= 60 ? " min" : ""} per pick` : " · no timer"}
          {d.scheduledStart && ` · planned ${new Date(d.scheduledStart).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}`}
        </p>)}
        {card(5, "Season", <p>
          {r.competition.format === "ROUND_ROBIN" ? `Round-robin${(r.competition.roundRobinCycles ?? 1) > 1 ? ` ×${r.competition.roundRobinCycles}` : ""}` : "Format not chosen"}
          {` · best of ${r.competition.bestOf}`}
          {` · ${r.transactions.tradesPerWeek ?? "unlimited"} trades, ${r.transactions.freeAgencyPerWeek ?? "unlimited"} claims a week`}
          {r.playoffs.enabled ? ` · playoffs (${r.playoffs.directQualifiers ?? "?"} qualify)` : " · no playoffs"}
        </p>)}
      </div>

      <h5>Ready to draft?</h5>
      <ul className="setup-steps">
        {d.readiness.map((c) => (
          <li key={c.item} className={c.ok ? "ok" : undefined}>
            <span className="step-mark" aria-hidden="true">{c.ok ? "✓" : ""}</span><span className="grow">{c.detail}</span>
          </li>
        ))}
      </ul>
      <p className="muted small">
        {ready ? "Next, on the Overview: draw the seat order, then start the draft when everyone's ready."
          : "You can finish now and sort out the rest from the Overview; the draft can't start until everything is ready."}
      </p>
      <WizardFooter back={back} next={() => void finish()} label="Finish setup" />
    </>
  );
}
