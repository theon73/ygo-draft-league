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

function Clock({ draft: d }: { draft: DraftState }) {
  const c = d.onTheClock;
  if (!c) return null;
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
      </span>
    </p>
  );
}

type Unit = "seconds" | "minutes" | "hours";
const UNIT_SECONDS: Record<Unit, number> = { seconds: 1, minutes: 60, hours: 3600 };

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
  // The timer is edited as an amount and a unit, and saved in seconds.
  const initialUnit: Unit = !d.timer ? "minutes" : d.timer.seconds % 3600 === 0 ? "hours" : d.timer.seconds % 60 === 0 ? "minutes" : "seconds";
  const [timerOn, setTimerOn] = useState(d.timer !== null);
  const [amount, setAmount] = useState(d.timer ? String(d.timer.seconds / UNIT_SECONDS[initialUnit]) : "2");
  const [unit, setUnit] = useState<Unit>(initialUnit);
  const [onExpiry, setOnExpiry] = useState<TimerExpiry>(d.timer?.onExpiry ?? "AUTO_PICK");
  const [pauseOn, setPauseOn] = useState(!!d.timer?.pauseFrom);
  const [pauseFrom, setPauseFrom] = useState(d.timer?.pauseFrom?.slice(0, 5) ?? "23:00");
  const [pauseTo, setPauseTo] = useState(d.timer?.pauseTo?.slice(0, 5) ?? "08:00");
  const [error, setError] = useState<string | null>(null);

  const rounds = toRounds(blocks);
  const picksEach = rounds.reduce((n, r) => n + (r.toRound - r.fromRound + 1) * r.picksPerTurn, 0);
  const setBlock = (i: number, b: Partial<Block>) => setBlocks((bs) => bs.map((x, j) => (j === i ? { ...x, ...b } : x)));

  const save = async () => {
    setError(null);
    try {
      await apiPut(`${base}/settings`, {
        order, rounds, scheduledStart: start || null, forbiddenDraftable: forbidden,
        ...(timerOn
          ? { timerSeconds: Math.round(Number(amount) * UNIT_SECONDS[unit]), timerExpiry: onExpiry, ...(pauseOn ? { pauseFrom, pauseTo } : {}) }
          : { timerSeconds: 0 }),
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
      <label className="check">
        <input type="checkbox" checked={timerOn} onChange={(e) => setTimerOn(e.target.checked)} /> Each pick has a time limit
      </label>
      {timerOn && (
        <>
          <p className="round-block">
            <input type="number" className="narrow-num" min={1} value={amount} onChange={(e) => setAmount(e.target.value)} aria-label="Time per pick" />{" "}
            <select value={unit} onChange={(e) => setUnit(e.target.value as Unit)} aria-label="Unit">
              <option value="seconds">seconds</option>
              <option value="minutes">minutes</option>
              <option value="hours">hours</option>
            </select>{" "}
            per pick; when it runs out{" "}
            <select value={onExpiry} onChange={(e) => setOnExpiry(e.target.value as TimerExpiry)} aria-label="When time runs out">
              <option value="AUTO_PICK">auto-pick from the player's queue (skip if empty)</option>
              <option value="SKIP">skip (the player makes it up later)</option>
              <option value="WAIT">wait for the pick</option>
            </select>
          </p>
          <label className="check">
            <input type="checkbox" checked={pauseOn} onChange={(e) => setPauseOn(e.target.checked)} /> Pause overnight from{" "}
            <input type="time" value={pauseFrom} onChange={(e) => setPauseFrom(e.target.value)} disabled={!pauseOn} aria-label="Pause from" /> to{" "}
            <input type="time" value={pauseTo} onChange={(e) => setPauseTo(e.target.value)} disabled={!pauseOn} aria-label="Pause until" />{" "}
            <span className="muted">({d.timeZone.replaceAll("_", " ")}; the clock stops in between, for drafts that run over days)</span>
          </label>
          <p className="muted">
            Short timers (seconds or minutes) suit a live draft; hours suit a slow one. Skipped picks can be made up until the
            draft ends; any still owed when the last pick is made are forfeited.
          </p>
        </>
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
