import { useState } from "react";
import { Link } from "react-router-dom";
import { apiPost, apiPut, type DraftState, type RoundBlock } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";

// The draft room (the season's Draft tab). Before the start: settings, a board preview, the
// readiness checklist and Start draft. Picking during the draft arrives in YGO-16.

export default function SeasonDraftPage() {
  const { league, reload: reloadLeague } = useLeague();
  const { season, reload: reloadSeason } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/draft`;
  const draft = useApi<DraftState>(base);
  const [error, setError] = useState<string | null>(null);

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
            <DraftSettings key={JSON.stringify([d.order, d.rounds, d.scheduledStart, d.forbiddenDraftable])} draft={d} base={base} onSaved={draft.reload} />
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
            {d.status === "IN_PROGRESS" ? "The draft is under way" : "The draft is complete"}: started
            {d.startedAt && ` ${new Date(d.startedAt).toLocaleString()}`}{d.startedBy && ` by ${d.startedBy}`}.
            {d.status === "IN_PROGRESS" && " Making picks comes next."}
          </p>
        </>
      )}
      <Board draft={d} />
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
      </span>
    </p>
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
  const [error, setError] = useState<string | null>(null);

  const rounds = toRounds(blocks);
  const picksEach = rounds.reduce((n, r) => n + (r.toRound - r.fromRound + 1) * r.picksPerTurn, 0);
  const setBlock = (i: number, b: Partial<Block>) => setBlocks((bs) => bs.map((x, j) => (j === i ? { ...x, ...b } : x)));

  const save = async () => {
    setError(null);
    try {
      await apiPut(`${base}/settings`, { order, rounds, scheduledStart: start || null, forbiddenDraftable: forbidden });
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
      <p><button onClick={() => void save()}>Save draft settings</button></p>
      {error && <p className="bad">{error}</p>}
    </>
  );
}

// Rounds down the side, seats across the top; each cell shows that seat's pick numbers in that round.
function Board({ draft: d }: { draft: DraftState }) {
  if (d.board.length === 0) return <p className="muted">The board appears once there are players and rounds.</p>;
  const cell = new Map<string, number[]>();
  for (const s of d.board) {
    const key = `${s.round}-${s.seat}`;
    cell.set(key, [...(cell.get(key) ?? []), s.overallPick]);
  }
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
                <th key={s.seat}>
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
                  <td key={s.seat} className="slot">{(cell.get(`${round}-${s.seat}`) ?? []).map((n) => `#${n}`).join(" ")}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
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
