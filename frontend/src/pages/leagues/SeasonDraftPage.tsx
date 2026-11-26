import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  apiPost, apiPut, type DraftQueueState, type DraftSlot, type DraftState, type PickableSlot, type PickTimer, type PoolCard, type PoolCardPage,
  type PoolFacets, type PoolSummary, type RoundBlock, type TimerExpiry,
} from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import PoolFilters from "./PoolFilters";
import PoolGrid from "./PoolGrid";
import { poolQueryString } from "./poolQuery";
import { useSeason } from "./SeasonLayout";
import { usePoolParams } from "./usePoolParams";

// The draft room (the season's Draft tab). Before the start: settings, a board preview, the
// readiness checklist and Start draft. During: who's on the clock, the board filling in, and the
// available cards to pick from. After: the final board. It refetches whenever the live connection
// (held by SeasonLayout) says the draft changed. Players also get their private queue and away
// switch, before and during the draft.

export default function SeasonDraftPage() {
  const { league, reload: reloadLeague } = useLeague();
  const { season, reload: reloadSeason, draftVersion } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/draft`;
  const draft = useApi<DraftState>(base, String(draftVersion));
  const [error, setError] = useState<string | null>(null);
  // Players' own queue; refetched with the draft (an auto-pick or someone's pick changes it).
  const playing = draft.data?.mySeat != null && draft.data.status !== "COMPLETE";
  const queue = useApi<DraftQueueState>(playing ? `${base}/queue` : null, String(draftVersion));

  if (draft.error) return <p className="bad">Couldn't load the draft ({draft.error})</p>;
  if (!draft.data) return <p className="muted">Loading…</p>;
  const d = draft.data;

  const start = async () => {
    if (!window.confirm("Start the draft? After this the players, seats and calendar are fixed, and the pool can't be regenerated.")) return;
    setError(null);
    try {
      await apiPost(`${base}/start`);
      draft.reload();
      reloadSeason();
      reloadLeague();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      {d.status === "NOT_STARTED" ? (
        <>
          {d.canManage ? (
            <DraftSettings key={JSON.stringify([d.order, d.rounds, d.scheduledStart, d.forbiddenDraftable, d.timer])} draft={d} base={base} onSaved={draft.reload} />
          ) : (
            <DraftSummary draft={d} />
          )}
          <h4>Ready to start?</h4>
          <ul className="checklist">
            {d.readiness.map((c) => (
              <li key={c.item} className={c.ok ? "good" : "bad"}>{c.ok ? "✓" : "✗"} {c.detail}</li>
            ))}
          </ul>
          {d.canManage && (
            <p>
              <button onClick={() => void start()} disabled={d.readiness.some((c) => !c.ok)}>Start draft</button>{" "}
              <span className="muted">Picking opens as soon as it starts.</span>
            </p>
          )}
          {error && <p className="bad">{error}</p>}
        </>
      ) : (
        <>
          <DraftSummary draft={d} />
          <p className="muted">
            Started{d.startedAt && ` ${new Date(d.startedAt).toLocaleString()}`}{d.startedBy && ` by ${d.startedBy}`}
            {d.completedAt ? `; completed ${new Date(d.completedAt).toLocaleString()}. The season is under way.` : "."}
            {" "}{d.picksMade} of {d.totalPicks} picks made.
          </p>
          {d.status === "IN_PROGRESS" && d.canManage && <CommissionerControls draft={d} base={base} onDone={() => { draft.reload(); reloadSeason(); reloadLeague(); }} />}
          {d.status === "IN_PROGRESS" && <Clock draft={d} />}
        </>
      )}
      <Board draft={d} />
      {playing && queue.data && <QueuePanel base={base} queue={queue.data} onChanged={() => { queue.reload(); draft.reload(); }} />}
      {(d.status === "IN_PROGRESS" || playing) && (
        <Picker draft={d} base={base} queue={playing ? queue.data : null} onQueued={queue.reload} onPicked={() => draft.reload()} />
      )}
    </>
  );
}

// Pause/resume, undo the last pick, and (before any pick) back to setup.
function CommissionerControls({ draft: d, base, onDone }: { draft: DraftState; base: string; onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editingTimer, setEditingTimer] = useState(false);
  const [timer, setTimer] = useState<TimerForm>(() => timerForm(d.timer));
  const saveTimer = async () => {
    setBusy(true);
    setError(null);
    try {
      await apiPut(`${base}/timer`, timerBody(timer));
      setEditingTimer(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      onDone();
    }
  };
  const act = async (path: string, confirm?: string) => {
    if (confirm && !window.confirm(confirm)) return;
    setBusy(true);
    setError(null);
    try {
      await apiPost(`${base}/${path}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      onDone();
    }
  };
  const u = d.undoable;
  const undoLabel = u && `#${u.overallPick} ${u.displayName}: ${u.card ?? "skipped"}${u.kind === "AUTO" ? " (auto)" : ""}${u.makeup ? " (make-up)" : ""}`;
  const undoNote = u && (u.makeup
    ? "The pick will be owed again."
    : `${u.displayName} will be on the clock again${d.timer ? " with a fresh timer" : ""}.`)
    + (u?.kind === "AUTO" ? ` Their away mode turns off so it isn't picked again.` : "");
  return (
    <div className="commish">
      <span className="muted">Commissioner:</span>{" "}
      {d.held
        ? <button onClick={() => void act("resume")} disabled={busy}>Resume draft</button>
        : <button onClick={() => void act("pause")} disabled={busy}>Pause draft</button>}{" "}
      {u && (
        <button onClick={() => void act("undo", `Undo ${undoLabel}? ${undoNote} The undo is kept in the draft log.`)} disabled={busy}
                title={`Undo ${undoLabel}`}>
          Undo last pick
        </button>
      )}{" "}
      {d.canUnstart && (
        <button onClick={() => void act("unstart", "Take the draft back to setup? Its log (including undone picks) is cleared, and players, seats, the pool and the calendar can change again.")}
                disabled={busy}>
          Back to setup
        </button>
      )}
      {" "}<button onClick={() => setEditingTimer((v) => !v)} disabled={busy}>{editingTimer ? "Cancel timer change" : "Change timer"}</button>
      {u && <span className="muted"> Last: {undoLabel}</span>}
      {editingTimer && (
        <div className="timer-change">
          <TimerFields value={timer} onChange={setTimer} timeZone={d.timeZone} />
          <p>
            <button onClick={() => void saveTimer()} disabled={busy}>Save timer</button>{" "}
            <span className="muted">The pick on the clock gets the full new time from now (from the resume, if paused).</span>
          </p>
        </div>
      )}
      {error && <p className="bad">{error}</p>}
    </div>
  );
}

function Clock({ draft: d }: { draft: DraftState }) {
  const c = d.onTheClock;
  if (!c) return null;
  if (d.held) {
    return (
      <p className="on-clock paused">
        <strong>The draft is paused</strong>{d.heldBy && ` by ${d.heldBy}`}{d.heldSince && ` since ${new Date(d.heldSince).toLocaleTimeString()}`}.
        {" "}{c.displayName} is up next with pick #{c.overallPick}{d.timer && "; their timer is stopped"}.
      </p>
    );
  }
  const mine = d.mySeat === c.seat;
  const myOwed = d.owed.filter((o) => o.seat === d.mySeat);
  return (
    <>
      <p className={`on-clock${mine ? " mine" : ""}`}>
        {mine ? <strong>You're on the clock</strong> : <><strong>{c.displayName}</strong> is on the clock</>}
        {" "}with pick #{c.overallPick} (round {c.round}{c.picksInTurn > 1 && `, pick ${c.pickInTurn} of ${c.picksInTurn} this turn`}).
        {d.timer && d.deadline && <> <Countdown draft={d} timer={d.timer} deadline={d.deadline} /></>}
        {!mine && d.canManage && <span className="muted"> As a commissioner you can pick for them.</span>}
      </p>
      {myOwed.length > 0 && (
        <p className="on-clock">
          You have {myOwed.length === 1 ? "a skipped pick" : `${myOwed.length} skipped picks`} to make up
          ({myOwed.map((o) => `#${o.overallPick}`).join(", ")}). Choose it under "Picking for" below; it's yours until the draft ends.
        </p>
      )}
      {d.canManage && d.owed.length > myOwed.length && (
        <p className="muted">
          Skipped picks still owed: {d.owed.filter((o) => o.seat !== d.mySeat).map((o) => `#${o.overallPick} ${o.displayName}`).join(", ")}.
        </p>
      )}
    </>
  );
}

const EXPIRY_LABEL: Record<TimerExpiry, string> = {
  WAIT: "the draft waits",
  SKIP: "the pick is skipped (made up later)",
  AUTO_PICK: "it auto-picks from the queue (or skips)",
};

// Time left on the pick, ticking every second. The server's clock is the reference: the offset
// between serverTime and the browser's clock when the draft was fetched corrects for a wrong local clock.
function Countdown({ draft: d, timer, deadline }: { draft: DraftState; timer: PickTimer; deadline: string }) {
  const [offset] = useState(() => Date.parse(d.serverTime) - Date.now());
  const [now, setNow] = useState(() => Date.now() + offset);
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now() + offset), 1000);
    return () => window.clearInterval(t);
  }, [offset]);
  const left = Date.parse(deadline) - now;
  if (d.paused) {
    return <span className="countdown">Paused overnight until {timer.pauseTo?.slice(0, 5)} ({d.timeZone.replaceAll("_", " ")}).</span>;
  }
  if (left <= 0) {
    return <span className="countdown overdue">Time's up{timer.onExpiry === "WAIT" ? ": waiting for the pick." : "…"}</span>;
  }
  return <span className={`countdown${left < 30_000 ? " overdue" : ""}`}>{formatLeft(left)} left.</span>;
}

function formatLeft(ms: number): string {
  const s = Math.ceil(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}:${String(s % 60).padStart(2, "0")}`;
}

function describeTimer(t: PickTimer | null): string {
  if (!t) return "No pick timer.";
  const pause = t.pauseFrom && t.pauseTo ? `, paused ${t.pauseFrom.slice(0, 5)} to ${t.pauseTo.slice(0, 5)}` : "";
  return `${formatDuration(t.seconds)} per pick${pause}; when it runs out, ${EXPIRY_LABEL[t.onExpiry]}.`;
}

function formatDuration(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600} hour${seconds === 3600 ? "" : "s"}`;
  if (seconds % 60 === 0) return `${seconds / 60} minute${seconds === 60 ? "" : "s"}`;
  return `${seconds} seconds`;
}

// The player's private queue: reorder, remove, and the away switch.
function QueuePanel({ base, queue, onChanged }: { base: string; queue: DraftQueueState; onChanged: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const act = async (change: () => Promise<unknown>) => {
    setError(null);
    setBusy(true);
    try {
      await change();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      onChanged();
    }
  };
  const ids = queue.cards.map((c) => c.cardId);
  const save = (next: number[]) => act(() => apiPut(`${base}/queue`, { cardIds: next }));
  const move = (i: number, by: number) => {
    const next = [...ids];
    [next[i], next[i + by]] = [next[i + by], next[i]];
    void save(next);
  };

  return (
    <>
      <h4>My queue <span className="muted">(only you see it)</span></h4>
      <label className="check">
        <input type="checkbox" checked={queue.away} disabled={busy}
               onChange={(e) => void act(() => apiPut(`${base}/away`, { away: e.target.checked }))} />{" "}
        Away: pick the first available card in my queue as soon as I'm on the clock{" "}
        <span className="muted">(turns off when nothing in it is left)</span>
      </label>
      {queue.cards.length === 0 ? (
        <p className="muted">Empty. Add cards with the Queue button on the cards below.</p>
      ) : (
        <ol className="queue">
          {queue.cards.map((c, i) => (
            <li key={c.cardId} className={c.available ? undefined : "taken"}>
              <CardPreview cardId={c.cardId}><CardImage passcode={c.passcode} size="small" alt="" className="queue-thumb" /></CardPreview>
              <span className="queue-name">{c.name}{!c.available && <span className="muted"> (no longer available)</span>}</span>
              <button onClick={() => move(i, -1)} disabled={busy || i === 0} aria-label={`Move ${c.name} up`}>↑</button>
              <button onClick={() => move(i, 1)} disabled={busy || i === ids.length - 1} aria-label={`Move ${c.name} down`}>↓</button>
              <button className="link" onClick={() => void save(ids.filter((id) => id !== c.cardId))} disabled={busy}>remove</button>
            </li>
          ))}
        </ol>
      )}
      {error && <p className="bad">{error}</p>}
    </>
  );
}

// The available cards, with the pool's filters (kept in this page's URL). Everyone can browse them
// to plan; players can queue them; whoever may fill a slot gets a Draft button on each card, for the
// slot chosen under "Picking for" (the one on the clock, or a skipped pick being made up).
function Picker({ draft: d, base, queue, onQueued, onPicked }: {
  draft: DraftState; base: string; queue: DraftQueueState | null; onQueued: () => void; onPicked: () => void;
}) {
  const { league } = useLeague();
  const { season } = useSeason();
  const poolBase = `/leagues/${league.id}/seasons/${season.id}/pool`;
  const { params, setParam, setParams, setList, clearFilters } = usePoolParams();
  const page = Number(params.get("page") ?? 0);
  const query = new URLSearchParams(poolQueryString(params, page, 50));
  query.set("available", "true");
  // Refetch whenever a pick is made, so taken cards drop out.
  const cards = useApi<PoolCardPage>(`${poolBase}/cards?${query}`, String(d.picksMade));
  const facets = useApi<PoolFacets>(`${poolBase}/facets`);
  const summary = useApi<PoolSummary>(poolBase);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<number | null>(null);
  // The slot to fill: the one chosen, if still pickable, else the first (the clock comes first).
  const slot: PickableSlot | undefined = d.pickable.find((p) => p.overallPick === chosen) ?? d.pickable[0];
  const queued = new Set(queue?.cards.map((c) => c.cardId) ?? []);

  const pick = async (c: PoolCard) => {
    if (!slot) return;
    const forWhom = d.mySeat === slot.seat ? "" : ` for ${slot.displayName}`;
    const what = slot.makeup ? `skipped pick #${slot.overallPick}` : `pick #${slot.overallPick}`;
    if (!window.confirm(`Draft ${c.name}${forWhom} with ${what}? Picks are final.`)) return;
    setBusy(true);
    setError(null);
    try {
      // overallPick guards against a stale board: if someone else's pick landed first, it's refused.
      await apiPost(`${base}/picks`, { cardId: c.cardId, overallPick: slot.overallPick });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      onPicked();
    }
  };
  const addToQueue = async (c: PoolCard) => {
    setError(null);
    try {
      await apiPost(`${base}/queue`, { cardId: c.cardId });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      onQueued();
    }
  };
  const actions = (c: PoolCard) => (
    <>
      {slot && <button onClick={() => void pick(c)} disabled={busy}>Draft</button>}{" "}
      {queue && (queued.has(c.cardId)
        ? <span className="muted">queued</span>
        : <button onClick={() => void addToQueue(c)}>Queue</button>)}
    </>
  );

  const lastPage = cards.data ? Math.max(0, Math.ceil(cards.data.total / cards.data.pageSize) - 1) : 0;
  return (
    <>
      <h4>Available cards <span className="muted">({d.availableCards.toLocaleString()} left)</span></h4>
      {d.pickable.length > 1 && (
        <label className="inline-label">Picking for{" "}
          <select value={slot?.overallPick} onChange={(e) => setChosen(Number(e.target.value))}>
            {d.pickable.map((p) => (
              <option key={p.overallPick} value={p.overallPick}>
                #{p.overallPick} {p.displayName}{p.makeup ? " (skipped, make-up)" : " (on the clock)"}
              </option>
            ))}
          </select>
        </label>
      )}
      {error && <p className="bad">{error}</p>}
      <PoolFilters key={`${params.get("q") ?? ""}|${params.get("cardText") ?? ""}`} params={params} setOne={setParam} setSome={setParams} setList={setList}
                   clear={clearFilters} facets={facets.data} hasBanList={!!summary.data?.banList} />
      {cards.error && <p className="bad">Couldn't load cards ({cards.error})</p>}
      {cards.data && (
        <>
          <p className="muted">
            {cards.data.total.toLocaleString()} card{cards.data.total === 1 ? "" : "s"}
            {cards.data.total > cards.data.pageSize && ` · page ${page + 1} of ${lastPage + 1}`}
          </p>
          <PoolGrid cards={cards.data.items}
                    action={slot || queue ? actions : undefined} />
          {cards.data.total > cards.data.pageSize && (
            <p>
              <button disabled={page === 0} onClick={() => setParam("page", String(page - 1))}>Previous</button>{" "}
              <button disabled={page >= lastPage} onClick={() => setParam("page", String(page + 1))}>Next</button>
            </p>
          )}
        </>
      )}
    </>
  );
}

function DraftSummary({ draft: d }: { draft: DraftState }) {
  return (
    <p>
      {d.order === "SNAKE" ? "Snake" : "Linear"} draft
      {d.roundCount > 0 ? `: ${d.roundCount} rounds, ${d.picksPerPlayer} picks each, ${d.totalPicks} in all` : " (rounds not set yet)"}.
      {d.scheduledStart && <> Planned for {when(d.scheduledStart, d.timeZone)}.</>}
      <br />
      <span className="muted">
        {d.draftableCards.toLocaleString()} draftable cards{d.forbiddenDraftable ? " (Forbidden cards can be drafted)" : " (Forbidden and soft-banned cards excluded)"}.
        {" "}{describeTimer(d.timer)}
        {d.timerChangedBy && d.timerChangedAt && ` (changed by ${d.timerChangedBy}, ${new Date(d.timerChangedAt).toLocaleTimeString()})`}
      </span>
    </p>
  );
}

type Unit = "seconds" | "minutes" | "hours";
const UNIT_SECONDS: Record<Unit, number> = { seconds: 1, minutes: 60, hours: 3600 };

// The pick timer as edited: an amount and a unit (saved in seconds), plus the optional overnight pause.
interface TimerForm { on: boolean; amount: string; unit: Unit; onExpiry: TimerExpiry; pauseOn: boolean; pauseFrom: string; pauseTo: string }

function timerForm(t: PickTimer | null): TimerForm {
  const unit: Unit = !t ? "minutes" : t.seconds % 3600 === 0 ? "hours" : t.seconds % 60 === 0 ? "minutes" : "seconds";
  return {
    on: t !== null, amount: t ? String(t.seconds / UNIT_SECONDS[unit]) : "2", unit, onExpiry: t?.onExpiry ?? "AUTO_PICK",
    pauseOn: !!t?.pauseFrom, pauseFrom: t?.pauseFrom?.slice(0, 5) ?? "23:00", pauseTo: t?.pauseTo?.slice(0, 5) ?? "08:00",
  };
}

/** The request fields for a timer (timerSeconds 0 = no timer). */
function timerBody(f: TimerForm) {
  if (!f.on) return { timerSeconds: 0 };
  return {
    timerSeconds: Math.round(Number(f.amount) * UNIT_SECONDS[f.unit]), timerExpiry: f.onExpiry,
    ...(f.pauseOn ? { pauseFrom: f.pauseFrom, pauseTo: f.pauseTo } : {}),
  };
}

// The timer controls, shared by the draft settings (before the start) and the commissioner bar (during).
function TimerFields({ value: f, onChange, timeZone }: { value: TimerForm; onChange: (f: TimerForm) => void; timeZone: string }) {
  const set = (patch: Partial<TimerForm>) => onChange({ ...f, ...patch });
  return (
    <>
      <label className="check">
        <input type="checkbox" checked={f.on} onChange={(e) => set({ on: e.target.checked })} /> Each pick has a time limit
      </label>
      {f.on && (
        <>
          <p className="round-block">
            <input type="number" className="narrow-num" min={1} value={f.amount} onChange={(e) => set({ amount: e.target.value })} aria-label="Time per pick" />{" "}
            <select value={f.unit} onChange={(e) => set({ unit: e.target.value as Unit })} aria-label="Unit">
              <option value="seconds">seconds</option>
              <option value="minutes">minutes</option>
              <option value="hours">hours</option>
            </select>{" "}
            per pick; when it runs out{" "}
            <select value={f.onExpiry} onChange={(e) => set({ onExpiry: e.target.value as TimerExpiry })} aria-label="When time runs out">
              <option value="AUTO_PICK">auto-pick from the player's queue (skip if empty)</option>
              <option value="SKIP">skip (the player makes it up later)</option>
              <option value="WAIT">wait for the pick</option>
            </select>
          </p>
          <label className="check">
            <input type="checkbox" checked={f.pauseOn} onChange={(e) => set({ pauseOn: e.target.checked })} /> Pause overnight from{" "}
            <input type="time" value={f.pauseFrom} onChange={(e) => set({ pauseFrom: e.target.value })} disabled={!f.pauseOn} aria-label="Pause from" /> to{" "}
            <input type="time" value={f.pauseTo} onChange={(e) => set({ pauseTo: e.target.value })} disabled={!f.pauseOn} aria-label="Pause until" />{" "}
            <span className="muted">({timeZone.replaceAll("_", " ")}; the clock stops in between, for drafts that run over days)</span>
          </label>
        </>
      )}
    </>
  );
}

// Round blocks are edited as "N rounds at P picks per turn" and saved as contiguous round ranges.
interface Block { rounds: string; picks: string }
const toBlocks = (rounds: RoundBlock[]): Block[] =>
  rounds.length ? rounds.map((r) => ({ rounds: String(r.toRound - r.fromRound + 1), picks: String(r.picksPerTurn) })) : [{ rounds: "", picks: "1" }];
function toRounds(blocks: Block[]): RoundBlock[] {
  let next = 1;
  return blocks.filter((b) => Number(b.rounds) > 0).map((b) => {
    const r = { fromRound: next, toRound: next + Number(b.rounds) - 1, picksPerTurn: Number(b.picks) || 1 };
    next = r.toRound + 1;
    return r;
  });
}

function DraftSettings({ draft: d, base, onSaved }: { draft: DraftState; base: string; onSaved: () => void }) {
  const [order, setOrder] = useState(d.order);
  const [blocks, setBlocks] = useState<Block[]>(toBlocks(d.rounds));
  const [start, setStart] = useState(d.scheduledStart ? localInput(d.scheduledStart, d.timeZone) : "");
  const [forbidden, setForbidden] = useState(d.forbiddenDraftable);
  const [timer, setTimer] = useState<TimerForm>(() => timerForm(d.timer));
  const [error, setError] = useState<string | null>(null);

  const rounds = toRounds(blocks);
  const picksEach = rounds.reduce((n, r) => n + (r.toRound - r.fromRound + 1) * r.picksPerTurn, 0);
  const setBlock = (i: number, b: Partial<Block>) => setBlocks((bs) => bs.map((x, j) => (j === i ? { ...x, ...b } : x)));

  const save = async () => {
    setError(null);
    try {
      await apiPut(`${base}/settings`, {
        order, rounds, scheduledStart: start || null, forbiddenDraftable: forbidden, ...timerBody(timer),
      });
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <h4>Draft settings</h4>
      <div className="form-grid">
        <label>Order
          <select value={order} onChange={(e) => setOrder(e.target.value as DraftState["order"])}>
            <option value="SNAKE">Snake (direction flips each round)</option>
            <option value="LINEAR">Linear (same order every round)</option>
          </select>
        </label>
        <label>Planned start ({d.timeZone.replaceAll("_", " ")})
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
      </div>
      <p className="muted">The planned start is shown to the league; a commissioner starts the draft.</p>

      <h5>Rounds</h5>
      {blocks.map((b, i) => (
        <p key={i} className="round-block">
          {i === 0 ? "First" : "Then"}{" "}
          <input type="number" className="narrow-num" min={1} value={b.rounds} onChange={(e) => setBlock(i, { rounds: e.target.value })} aria-label="Rounds" />{" "}
          rounds at{" "}
          <select value={b.picks} onChange={(e) => setBlock(i, { picks: e.target.value })} aria-label="Picks per turn">
            {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>{" "}
          pick{b.picks === "1" ? "" : "s"} per turn
          {blocks.length > 1 && <button type="button" className="link" onClick={() => setBlocks((bs) => bs.filter((_, j) => j !== i))}>remove</button>}
        </p>
      ))}
      <p>
        <button type="button" onClick={() => setBlocks((bs) => [...bs, { rounds: "", picks: "2" }])}>Add a block of rounds</button>{" "}
        <span className="muted">
          {rounds.length ? `${rounds[rounds.length - 1].toRound} rounds, ${picksEach} picks per player` : "e.g. 10 rounds at 1 pick, then 15 rounds at 2 picks"}
          {rounds.length > 0 && d.seats.length > 0 && `, ${picksEach * d.seats.length} picks in all`}
        </span>
      </p>

      <label className="check">
        <input type="checkbox" checked={forbidden} onChange={(e) => setForbidden(e.target.checked)} /> Forbidden cards can be drafted{" "}
        <span className="muted">(off by default: with one owner per card, a Forbidden card is a wasted pick)</span>
      </label>

      <h5>Pick timer</h5>
      <TimerFields value={timer} onChange={setTimer} timeZone={d.timeZone} />
      {timer.on && (
        <p className="muted">
          Short timers (seconds or minutes) suit a live draft; hours suit a slow one. Skipped picks can be made up until the
          draft ends; any still owed when the last pick is made are forfeited. Commissioners can change the timer during the draft.
        </p>
      )}
      <p><button onClick={() => void save()}>Save draft settings</button></p>
      {error && <p className="bad">{error}</p>}
    </>
  );
}

// Rounds down the side, seats across the top; each cell shows that seat's pick numbers in that round.
function Board({ draft: d }: { draft: DraftState }) {
  if (d.board.length === 0) return <p className="muted">The board appears once there are players and rounds.</p>;
  // A cell holds every slot that seat has in that round (several with multi-pick turns).
  const cell = new Map<string, DraftSlot[]>();
  for (const s of d.board) {
    const key = `${s.round}-${s.seat}`;
    cell.set(key, [...(cell.get(key) ?? []), s]);
  }
  const current = d.onTheClock?.overallPick;
  return (
    <>
      <h4>Draft board</h4>
      <p className="muted">
        Draft order follows the seats on the <Link to="..">Setup</Link> tab{d.status === "NOT_STARTED" && ", where they can be set by hand or drawn at random"}.
      </p>
      <div className="board-scroll">
        <table className="board">
          <thead>
            <tr>
              <th>Round</th>
              {d.seats.map((s) => (
                <th key={s.seat} className={s.seat === d.mySeat ? "mine" : undefined}>
                  {s.seat}. {s.displayName}
                  {s.teamName && <span className="muted"><br />{s.teamName}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: d.roundCount }, (_, i) => i + 1).map((round) => (
              <tr key={round}>
                <th>{round}</th>
                {d.seats.map((s) => (
                  <td key={s.seat} className="slot">
                    {(cell.get(`${round}-${s.seat}`) ?? []).map((slot) => (
                      <div key={slot.overallPick} className={slot.overallPick === current ? "current" : slot.pick ? "picked" : undefined}>
                        {slot.pick ? (
                          <CardPreview cardId={slot.pick.cardId}>
                            <span title={pickTitle(slot)}>
                              {slot.pick.name}
                              {slot.pick.auto && <sup className="muted"> auto</sup>}
                              {slot.pick.madeUp && <sup className="muted"> made up</sup>}
                            </span>
                          </CardPreview>
                        ) : slot.skipped ? <span className="skipped" title={`#${slot.overallPick}: time ran out`}>skipped</span> : `#${slot.overallPick}`}
                      </div>
                    ))}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function pickTitle(slot: DraftSlot): string {
  const p = slot.pick!;
  const how = p.auto ? "auto-picked from the queue" : `picked by ${p.madeBy}${p.onBehalf ? " for the player" : ""}`;
  return `#${slot.overallPick}, ${how}${p.madeUp ? " (made up after a skip)" : ""}`;
}

function when(instant: string, zone: string): string {
  return new Date(instant).toLocaleString(undefined, { timeZone: zone, weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit", timeZoneName: "short" });
}

/** An instant as the "YYYY-MM-DDTHH:mm" a datetime-local input shows, in the league's zone. */
function localInput(instant: string, zone: string): string {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
      .formatToParts(new Date(instant)).map((p) => [p.type, p.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}
