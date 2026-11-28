import PlayerAvatar from "../../components/PlayerAvatar";
import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  apiPost, apiPut, type DraftQueueState, type DraftSlot, type DraftState, type PickableSlot, type PickTimer, type PoolCard, type PoolCardPage,
  type PoolFacets, type PoolSummary, type RoundBlock, type TimerExpiry,
} from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { cardStats } from "../../components/cardStats";
import InfoTip from "../../components/InfoTip";
import StatTile, { ProgressBar } from "../../components/StatTile";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import PoolFilters from "./PoolFilters";
import PoolGrid from "./PoolGrid";
import { poolQueryString } from "./poolQuery";
import { seasonCardPath } from "./RostersPage";
import { useSeason } from "./SeasonLayout";
import { usePoolParams } from "./usePoolParams";
import { useConfirm, type ConfirmOptions, Loading } from "../../components/Feedback";

// The draft room (the season's Draft tab). Before the start: settings, a board preview, the
// readiness checklist and Start draft. During: who's on the clock, the latest picks, the board
// filling in, and the available cards to pick from. After: the final board. It refetches whenever
// the live connection (held by SeasonLayout) says the draft changed. Players also get their
// private queue and away switch, before and during the draft.
//
// YGO-29 layout: a summary row of stat tiles replaces the old paragraph, the clock is a banner,
// the latest picks show their card art, and when it's your pick the picker (with your queue
// beside it on a wide screen) comes before the board instead of under 40 rounds of it.

export default function SeasonDraftPage() {
  const { league, reload: reloadLeague } = useLeague();
  const { season, reload: reloadSeason, draftVersion } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/draft`;
  const draft = useApi<DraftState>(base, String(draftVersion));
  const [error, setError] = useState<string | null>(null);
  const confirm = useConfirm();
  // Players' own queue; refetched with the draft (an auto-pick or someone's pick changes it).
  const playing = draft.data?.mySeat != null && draft.data.status !== "COMPLETE";
  const queue = useApi<DraftQueueState>(playing ? `${base}/queue` : null, String(draftVersion));

  if (draft.error) return <p className="bad">Couldn't load the draft ({draft.error})</p>;
  if (!draft.data) return <Loading />;
  const d = draft.data;

  const start = async () => {
    if (!(await confirm({
      title: "Start the draft?",
      message: "Picking opens right away. After this the players, seats and calendar are fixed, and the pool can't be regenerated.",
      confirmLabel: "Start draft",
    }))) return;
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

  // Your own pick (on the clock, or a skipped one to make up) puts the picker before the board.
  const myTurn = d.status === "IN_PROGRESS" && d.pickable.some((p) => p.seat === d.mySeat);
  const showPicker = d.status === "IN_PROGRESS" || playing;
  const work = showPicker && (
    <div className={`draft-work${playing && queue.data ? " with-queue" : ""}`}>
      {playing && queue.data && (
        <aside className="draft-queue"><QueuePanel base={base} queue={queue.data} onChanged={() => { queue.reload(); draft.reload(); }} /></aside>
      )}
      <div className="draft-picker">
        <Picker draft={d} base={base} queue={playing ? queue.data : null} onQueued={queue.reload} onPicked={() => draft.reload()} />
      </div>
    </div>
  );
  const board = <BoardSection draft={d} />;

  return (
    <>
      {d.status === "NOT_STARTED" ? (
        <>
          {d.canManage ? (
            <DraftSettings key={JSON.stringify([d.order, d.rounds, d.scheduledStart, d.forbiddenDraftable, d.timer])} draft={d} base={base} onSaved={draft.reload} />
          ) : (
            <DraftSummary draft={d} />
          )}
          <section className="panel readiness">
            <h4>Ready to start?</h4>
            <ul className="checklist">
              {d.readiness.map((c) => (
                <li key={c.item} className={c.ok ? "good" : "bad"}>{c.ok ? "✓" : "✗"} {c.detail}</li>
              ))}
            </ul>
            {d.canManage && (
              <p className="readiness-actions">
                <button className="primary" onClick={() => void start()} disabled={d.readiness.some((c) => !c.ok)}>Start draft</button>
                <span className="muted">Picking opens as soon as it starts.</span>
              </p>
            )}
            {error && <p className="bad">{error}</p>}
          </section>
        </>
      ) : (
        <>
          {d.status === "IN_PROGRESS" && <ClockBanner draft={d} />}
          <DraftSummary draft={d} />
          <RecentPicks draft={d} />
          {d.status === "IN_PROGRESS" && d.canManage && <CommissionerControls draft={d} base={base} onDone={() => { draft.reload(); reloadSeason(); reloadLeague(); }} />}
        </>
      )}
      {myTurn ? <>{work}{board}</> : <>{board}{work}</>}
    </>
  );
}

// Pause/resume, undo the last pick, and (before any pick) back to setup.
function CommissionerControls({ draft: d, base, onDone }: { draft: DraftState; base: string; onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editingTimer, setEditingTimer] = useState(false);
  const [timer, setTimer] = useState<TimerForm>(() => timerForm(d.timer));
  const confirm = useConfirm();
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
  const act = async (path: string, ask?: ConfirmOptions) => {
    if (ask && !(await confirm(ask))) return;
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
      <span className="commish-label">Commissioner</span>
      {d.held
        ? <button onClick={() => void act("resume")} disabled={busy}>Resume draft</button>
        : <button onClick={() => void act("pause")} disabled={busy}>Pause draft</button>}{" "}
      {u && (
        <button onClick={() => void act("undo", { title: `Undo ${undoLabel}?`, message: `${undoNote} The undo is kept in the draft log.`, confirmLabel: "Undo pick", danger: true })} disabled={busy}
                title={`Undo ${undoLabel}`}>
          Undo last pick
        </button>
      )}{" "}
      {d.canUnstart && (
        <button onClick={() => void act("unstart", {
                  title: "Take the draft back to setup?",
                  message: "Its log (including undone picks) is cleared, and players, seats, the pool and the calendar can change again.",
                  confirmLabel: "Back to setup", danger: true,
                })}
                disabled={busy}>
          Back to setup
        </button>
      )}
      {" "}<button onClick={() => setEditingTimer((v) => !v)} disabled={busy}>{editingTimer ? "Cancel timer change" : "Change timer"}</button>
      {editingTimer && (
        <div className="timer-change">
          <TimerFields value={timer} onChange={setTimer} timeZone={d.timeZone} />
          <p>
            <button className="primary" onClick={() => void saveTimer()} disabled={busy}>Save timer</button>{" "}
            <InfoTip>The pick on the clock gets the full new time from now (from the resume, if the draft is paused).</InfoTip>
          </p>
        </div>
      )}
      {error && <p className="bad">{error}</p>}
    </div>
  );
}

// Who's on the clock, as a banner with a large countdown; gold when it's you.
function ClockBanner({ draft: d }: { draft: DraftState }) {
  const c = d.onTheClock;
  if (!c) return null;
  const turn = `Pick #${c.overallPick} · Round ${c.round}${c.picksInTurn > 1 ? ` · ${c.pickInTurn} of ${c.picksInTurn} this turn` : ""}`;
  if (d.held) {
    return (
      <div className="clock-banner paused" role="status">
        <div className="clock-who">
          <span className="clock-eyebrow">Draft paused</span>
          <strong>{d.heldBy ? `Paused by ${d.heldBy}` : "Paused"}</strong>
          <span className="clock-detail">
            {c.displayName} is up next · {turn}{d.heldSince && ` · since ${new Date(d.heldSince).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}`}
          </span>
        </div>
        <div className="clock-time"><span className="clock-left">❚❚</span>{d.timer && <span className="clock-caption">timer stopped</span>}</div>
      </div>
    );
  }
  const mine = d.mySeat === c.seat;
  const myOwed = d.owed.filter((o) => o.seat === d.mySeat);
  const othersOwed = d.owed.filter((o) => o.seat !== d.mySeat);
  return (
    <>
      <div className={`clock-banner${mine ? " mine" : ""}`} role="status">
        <div className="clock-who">
          <span className="clock-eyebrow">On the clock</span>
          <strong>{mine ? "You're up" : c.displayName}</strong>
          <span className="clock-detail">
            {turn}
            {!mine && d.canManage && <> · you can pick for them <InfoTip>As a commissioner you can make any pick on a player's behalf; it's marked as yours in the log.</InfoTip></>}
          </span>
        </div>
        {d.timer && d.deadline && <Countdown draft={d} timer={d.timer} deadline={d.deadline} />}
      </div>
      {myOwed.length > 0 && (
        <p className="callout">
          <strong>You owe {myOwed.length === 1 ? "a skipped pick" : `${myOwed.length} skipped picks`}</strong>
          <InfoTip>Your time ran out on these picks. Make them up whenever you like until the draft ends (choose one under "Picking for"); any still owed when the last pick is made are forfeited.</InfoTip>
          {" "}<span className="muted">{pickList(myOwed.map((o) => o.overallPick))}</span>
        </p>
      )}
      {d.canManage && othersOwed.length > 0 && (
        <p className="muted small">
          Still owed:{" "}
          {[...new Set(othersOwed.map((o) => o.displayName))].map((name) => {
            const picks = othersOwed.filter((o) => o.displayName === name).map((o) => o.overallPick);
            return `${name}: ${pickList(picks)}`;
          }).join(" · ")}
        </p>
      )}
    </>
  );
}

/** "#10, #11, #14 and 29 more": a long list of pick numbers, shortened. */
function pickList(picks: number[], show = 4): string {
  const head = picks.slice(0, show).map((n) => `#${n}`).join(", ");
  return picks.length > show ? `${head} and ${picks.length - show} more` : head;
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
    return (
      <div className="clock-time">
        <span className="clock-left small">Overnight</span>
        <span className="clock-caption">paused until {timer.pauseTo?.slice(0, 5)} ({d.timeZone.replaceAll("_", " ")})</span>
      </div>
    );
  }
  if (left <= 0) {
    return (
      <div className="clock-time overdue">
        <span className="clock-left">0:00</span>
        <span className="clock-caption">{timer.onExpiry === "WAIT" ? "time's up: waiting for the pick" : "time's up…"}</span>
      </div>
    );
  }
  return (
    <div className={`clock-time${left < 30_000 ? " overdue" : ""}`}>
      <span className="clock-left">{formatLeft(left)}</span>
      <span className="clock-caption">left to pick</span>
    </div>
  );
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

const EXPIRY_SHORT: Record<TimerExpiry, string> = { WAIT: "waits", SKIP: "skips", AUTO_PICK: "auto-pick" };

/** The timer's tile: "2 min" / "per pick · auto-pick", with the details in the tip. */
function timerTile(d: DraftState): { value: string; sub: string; tip: ReactNode } {
  const t = d.timer;
  if (!t) return { value: "None", sub: "no time limit", tip: "Picks have no time limit: the draft waits for each player." };
  const pause = t.pauseFrom && t.pauseTo;
  return {
    value: formatDuration(t.seconds, true),
    sub: `per pick · ${EXPIRY_SHORT[t.onExpiry]}${pause ? " · overnight pause" : ""}`,
    tip: (
      <>
        When the time runs out, {EXPIRY_LABEL[t.onExpiry]}.
        {pause && <> The clock stops from {t.pauseFrom!.slice(0, 5)} to {t.pauseTo!.slice(0, 5)} ({d.timeZone.replaceAll("_", " ")}).</>}
        {d.timerChangedBy && d.timerChangedAt && <> Changed by {d.timerChangedBy} at {new Date(d.timerChangedAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}.</>}
      </>
    ),
  };
}

function formatDuration(seconds: number, short = false): string {
  if (seconds % 3600 === 0) return short ? `${seconds / 3600} hr` : `${seconds / 3600} hour${seconds === 3600 ? "" : "s"}`;
  if (seconds % 60 === 0) return short ? `${seconds / 60} min` : `${seconds / 60} minute${seconds === 60 ? "" : "s"}`;
  return short ? `${seconds} sec` : `${seconds} seconds`;
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
      <h4 className="queue-head">
        My queue <span className="private" title="Only you see your queue"><LockIcon /> private</span>
        <InfoTip>Only you see your queue. If your timer runs out (or you're away), the first card in it that's still available is picked for you.</InfoTip>
      </h4>
      <label className="check away">
        <input type="checkbox" checked={queue.away} disabled={busy}
               onChange={(e) => void act(() => apiPut(`${base}/away`, { away: e.target.checked }))} />{" "}
        Away: auto-pick from my queue
        <InfoTip>As soon as you're on the clock, the first available card in your queue is picked for you, without waiting for the timer. It turns off by itself when nothing in your queue is left.</InfoTip>
      </label>
      {queue.cards.length === 0 ? (
        <p className="muted small">Empty. Use Queue on a card to add it.</p>
      ) : (
        <ol className="queue">
          {queue.cards.map((c, i) => (
            <li key={c.cardId} className={c.available ? undefined : "taken"}>
              <CardPreview cardId={c.cardId}><CardImage passcode={c.passcode} size="small" alt="" className="queue-thumb" /></CardPreview>
              <span className="queue-name">{c.name}{!c.available && <span className="muted"> (no longer available)</span>}</span>
              <button onClick={() => move(i, -1)} disabled={busy || i === 0} aria-label={`Move ${c.name} up`}>↑</button>
              <button onClick={() => move(i, 1)} disabled={busy || i === ids.length - 1} aria-label={`Move ${c.name} down`}>↓</button>
              <button className="icon" onClick={() => void save(ids.filter((id) => id !== c.cardId))} disabled={busy} aria-label={`Remove ${c.name}`} title="Remove">✕</button>
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
  const confirm = useConfirm();
  // The slot to fill: the one chosen, if still pickable, else the first (the clock comes first).
  const slot: PickableSlot | undefined = d.pickable.find((p) => p.overallPick === chosen) ?? d.pickable[0];
  const queued = new Set(queue?.cards.map((c) => c.cardId) ?? []);

  const pick = async (c: PoolCard) => {
    if (!slot) return;
    const forWhom = d.mySeat === slot.seat ? "" : ` for ${slot.displayName}`;
    if (!(await confirm({
      title: `Draft ${c.name}${forWhom}?`,
      media: <CardImage passcode={c.passcode} size="small" alt="" className="confirm-art" />,
      message: (
        <>
          <span className="confirm-stats">{/Spell|Trap/.test(c.cardType) ? cardStats(c) : `${c.cardType} · ${cardStats(c)}`}{c.banStatus ? ` · ${c.banStatus.replace("_", "-").toLowerCase()}` : ""}</span>
          <span className="confirm-slot">
            <strong>{slot.makeup ? "Make-up pick" : "Pick"} #{slot.overallPick}</strong> · Round {slot.round}
          </span>
          <span className="muted">Picks are final.</span>
        </>
      ),
      confirmLabel: "Draft it",
    }))) return;
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
      {slot && <button className="primary" onClick={() => void pick(c)} disabled={busy}>Draft</button>}{" "}
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

// The draft at a glance: format, progress, timer, pool and start, as a row of stat tiles.
export function DraftSummary({ draft: d }: { draft: DraftState }) {
  const timer = timerTile(d);
  const multiPick = d.rounds.some((r) => r.picksPerTurn > 1);
  const fmt = (instant: string) => new Date(instant).toLocaleString(undefined, { timeZone: d.timeZone, month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  return (
    <div className="stat-tiles draft-summary">
      <StatTile label="Format" value={d.order === "SNAKE" ? "Snake" : "Linear"}
                sub={d.roundCount > 0 ? `${d.roundCount} rounds · ${d.picksPerPlayer} picks each` : "rounds not set yet"}
                tip={<>
                  {d.order === "SNAKE" ? "The order reverses every round, so the last seat picks twice in a row at the turn." : "The same order every round."}
                  {multiPick && <> {d.rounds.map((r) => `rounds ${r.fromRound}–${r.toRound}: ${r.picksPerTurn} pick${r.picksPerTurn === 1 ? "" : "s"} per turn`).join("; ")}.</>}
                </>} />
      {d.status === "NOT_STARTED" ? (
        <StatTile label="Picks" value={d.totalPicks.toLocaleString()} sub={`for ${d.seats.length} player${d.seats.length === 1 ? "" : "s"}`} />
      ) : (
        <StatTile label="Progress" value={<>{d.picksMade}<span className="stat-of"> / {d.totalPicks}</span></>}
                  sub={d.status === "COMPLETE" ? "draft complete" : "picks made"}>
          <ProgressBar done={d.picksMade} total={d.totalPicks} />
        </StatTile>
      )}
      <StatTile label="Timer" value={timer.value} sub={timer.sub} tip={timer.tip} />
      <StatTile label="Pool" value={(d.status === "NOT_STARTED" ? d.draftableCards : d.availableCards).toLocaleString()}
                sub={d.status === "NOT_STARTED" ? "draftable cards" : `left of ${d.draftableCards.toLocaleString()} draftable`}
                tip={d.forbiddenDraftable
                  ? "Forbidden cards can be drafted this season."
                  : "Forbidden and soft-banned cards are left out: with one owner per card, they'd be wasted picks."} />
      {d.status === "NOT_STARTED" ? (
        <StatTile label="Planned start" value={d.scheduledStart ? fmt(d.scheduledStart) : "Not set"}
                  sub={d.scheduledStart ? d.timeZone.replaceAll("_", " ") : "a commissioner starts it"} />
      ) : d.status === "COMPLETE" && d.completedAt ? (
        <StatTile label="Completed" value={fmt(d.completedAt)} sub={d.startedAt ? `started ${fmt(d.startedAt)}` : undefined} />
      ) : (
        <StatTile label="Started" value={d.startedAt ? fmt(d.startedAt) : "—"} sub={d.startedBy ? `by ${d.startedBy}` : undefined} />
      )}
    </div>
  );
}

// The latest picks, newest first, with their card art: the last one large, then a strip of the
// few before it. Hover (or tap) a card for its full text, like the queue.
export function RecentPicks({ draft: d }: { draft: DraftState }) {
  const { league } = useLeague();
  const { season } = useSeason();
  const names = new Map(d.seats.map((s) => [s.seat, s.displayName]));
  const picks = d.board.filter((s) => s.pick).sort((a, b) => b.pick!.madeAt.localeCompare(a.pick!.madeAt)).slice(0, 7);
  if (picks.length === 0) return null;
  return (
    <section className="recent-picks" aria-label="Latest picks">
      <ol>
        {picks.map((s, i) => (
          <li key={s.overallPick} className={i === 0 ? "last" : undefined}>
            <CardPreview cardId={s.pick!.cardId}>
              <CardImage passcode={s.pick!.passcode} size="small" alt="" className="pick-art" />
            </CardPreview>
            <div className="pick-text">
              {i === 0 && <span className="pick-eyebrow">Last pick</span>}
              <span className="pick-no">#{s.overallPick}</span>
              <Link className="pick-name" to={seasonCardPath(league.id, season.id, s.pick!.cardId)} title={s.pick!.name}>{s.pick!.name}</Link>
              <span className="pick-by">
                {names.get(s.seat)}
                {s.pick!.auto && <span className="badge neutral">auto</span>}
                {s.pick!.madeUp && <span className="badge neutral">made up</span>}
              </span>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

function LockIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 16 16" aria-hidden="true" fill="currentColor">
      <path d="M4 7V5a4 4 0 1 1 8 0v2h.5A1.5 1.5 0 0 1 14 8.5v6a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 2 14.5v-6A1.5 1.5 0 0 1 3.5 7H4Zm2 0h4V5a2 2 0 1 0-4 0v2Z" />
    </svg>
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
            <input type="time" value={f.pauseTo} onChange={(e) => set({ pauseTo: e.target.value })} disabled={!f.pauseOn} aria-label="Pause until" />
            <InfoTip>Times are in {timeZone.replaceAll("_", " ")}. The clock stops in between, for drafts that run over several days.</InfoTip>
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

/** The draft's settings form; exported for the setup wizard (YGO-32), which renames the button and adds a Back. */
export function DraftSettings({ draft: d, base, onSaved, saveLabel, extraFooter }: {
  draft: DraftState; base: string; onSaved: () => void; saveLabel?: string; extraFooter?: ReactNode;
}) {
  const [order, setOrder] = useState(d.order);
  const [blocks, setBlocks] = useState<Block[]>(toBlocks(d.rounds));
  // The planned start is typed in a zone of the commissioner's choosing (the league's by default)
  // and sent in the league's zone, which is what the server expects.
  const [zone, setZone] = useState(d.timeZone);
  const [start, setStart] = useState(d.scheduledStart ? localInput(d.scheduledStart, d.timeZone) : "");
  const startInLeagueZone = start ? localInput(zonedToInstant(start, zone), d.timeZone) : null;
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
        order, rounds, scheduledStart: startInLeagueZone, forbiddenDraftable: forbidden, ...timerBody(timer),
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
        <label>Planned start
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label>Time zone
          <select value={zone} onChange={(e) => setZone(e.target.value)}>
            {(ZONES.includes(d.timeZone) ? ZONES : [d.timeZone, ...ZONES]).map((z) => (
              <option key={z} value={z}>{z.replaceAll("_", " ")}{z === d.timeZone ? " (league)" : ""}</option>
            ))}
          </select>
        </label>
      </div>
      <p className="muted small">
        Shown to the league <InfoTip>The planned start is only an announcement: the draft opens when a commissioner presses Start draft.</InfoTip>
        {start && zone !== d.timeZone && ` · that's ${when(zonedToInstant(start, zone), d.timeZone)} in the league's time zone.`}
      </p>

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
        <input type="checkbox" checked={forbidden} onChange={(e) => setForbidden(e.target.checked)} /> Forbidden cards can be drafted
        <InfoTip>Off by default: with one owner per card, a Forbidden card is a wasted pick.</InfoTip>
      </label>

      <h5>Pick timer <InfoTip>
        Short timers (seconds or minutes) suit a live draft; hours suit a slow one. Skipped picks can be made up until the
        draft ends; any still owed when the last pick is made are forfeited. Commissioners can change the timer during the draft.
      </InfoTip></h5>
      <TimerFields value={timer} onChange={setTimer} timeZone={d.timeZone} />
      <p className="form-actions">{extraFooter}<button className="primary" onClick={() => void save()}>{saveLabel ?? "Save draft settings"}</button></p>
      {error && <p className="bad">{error}</p>}
    </>
  );
}

// The board, or (for players) just their own picks as card tiles: the full board is 40-odd rows.
function BoardSection({ draft: d }: { draft: DraftState }) {
  const [view, setView] = useState<"board" | "mine">("board");
  const mine = d.mySeat != null && d.status !== "NOT_STARTED";
  return (
    <section className="board-section">
      <div className="section-head">
        <h4>
          {view === "mine" && mine ? "My picks" : "Draft board"}
          <InfoTip>
            Rounds run down the side and seats across the top. The order follows the seats on the Players tab
            {d.status === "NOT_STARTED" ? ", where they can be set by hand or drawn at random." : "."}
            {d.order === "SNAKE" && " In a snake draft every other round runs backwards."}
          </InfoTip>
        </h4>
        {mine && (
          <nav className="tabs small" aria-label="Board view">
            <button className={view === "board" ? "active" : undefined} onClick={() => setView("board")}>Full board</button>
            <button className={view === "mine" ? "active" : undefined} onClick={() => setView("mine")}>My picks</button>
          </nav>
        )}
        {d.status === "NOT_STARTED" && <Link className="button sm" to="../players">Set the seats</Link>}
      </div>
      {view === "mine" && mine ? <MyPicks draft={d} /> : <Board draft={d} />}
    </section>
  );
}

function MyPicks({ draft: d }: { draft: DraftState }) {
  const { league } = useLeague();
  const { season } = useSeason();
  const slots = d.board.filter((s) => s.seat === d.mySeat).sort((a, b) => a.overallPick - b.overallPick);
  const made = slots.filter((s) => s.pick);
  if (made.length === 0) return <p className="muted">No picks yet. Your first is #{slots[0]?.overallPick ?? "?"}.</p>;
  const next = slots.find((s) => !s.pick && !s.skipped);
  return (
    <>
      <p className="muted small">{made.length} of {slots.length} picks made{next && ` · next is #${next.overallPick} (round ${next.round})`}</p>
      <ul className="my-picks">
        {made.map((s) => (
          <li key={s.overallPick}>
            <CardPreview cardId={s.pick!.cardId}><CardImage passcode={s.pick!.passcode} size="small" alt="" className="tile-art" /></CardPreview>
            <span className="pick-no">#{s.overallPick} · R{s.round}</span>
            <Link className="tile-name" to={seasonCardPath(league.id, season.id, s.pick!.cardId)} title={s.pick!.name}>{s.pick!.name}</Link>
          </li>
        ))}
      </ul>
    </>
  );
}

// Rounds down the side, seats across the top; each cell shows that seat's pick(s) in that round,
// with the card's art once it's picked.
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
    <div className="board-scroll">
      <table className="board">
        <thead>
          <tr>
            <th>Rd</th>
            {d.seats.map((s) => (
              <th key={s.seat} className={s.seat === d.mySeat ? "mine" : undefined}>
                <span className="seat-head">
                  <PlayerAvatar name={s.displayName} passcode={s.avatar} size="sm" />
                  <span className="seat-no">{s.seat}</span> {s.displayName}
                </span>
                {s.teamName && <span className="seat-team">{s.teamName}</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: d.roundCount }, (_, i) => i + 1).map((round) => (
            <tr key={round}>
              <th>{round}</th>
              {d.seats.map((s) => (
                <td key={s.seat} className={`slot${s.seat === d.mySeat ? " mine" : ""}`}>
                  {(cell.get(`${round}-${s.seat}`) ?? []).map((slot) => (
                    <div key={slot.overallPick} className={slot.overallPick === current ? "current" : slot.pick ? "picked" : undefined}>
                      {slot.pick ? (
                        <CardPreview cardId={slot.pick.cardId}>
                          <span className="board-pick" title={pickTitle(slot)}>
                            <CardImage passcode={slot.pick.passcode} size="small" alt="" className="board-art" />
                            <span className="board-name">{slot.pick.name}</span>
                            {slot.pick.auto && <sup className="muted">auto</sup>}
                            {slot.pick.madeUp && <sup className="muted">made up</sup>}
                          </span>
                        </CardPreview>
                      ) : slot.skipped ? <span className="skipped" title={`#${slot.overallPick}: time ran out`}>skipped</span>
                        : slot.overallPick === current ? <span>#{slot.overallPick} · now</span> : `#${slot.overallPick}`}
                    </div>
                  ))}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
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

// Every IANA zone the browser knows, e.g. "America/Chicago".
const ZONES: string[] = Intl.supportedValuesOf("timeZone");

/** A "YYYY-MM-DDTHH:mm" wall-clock time in `zone` as an ISO instant (DST-aware). */
function zonedToInstant(local: string, zone: string): string {
  const asUtc = Date.parse(`${local}:00Z`);
  // The zone's offset at a moment: its wall clock read as UTC, minus the moment.
  const offset = (ms: number) => Date.parse(`${localInput(new Date(ms).toISOString(), zone)}:00Z`) - ms;
  let t = asUtc - offset(asUtc);
  t = asUtc - offset(t); // again at the result, in case a DST change lies in between
  return new Date(t).toISOString();
}

/** An instant as the "YYYY-MM-DDTHH:mm" a datetime-local input shows, in the league's zone. */
function localInput(instant: string, zone: string): string {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
      .formatToParts(new Date(instant)).map((p) => [p.type, p.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}
