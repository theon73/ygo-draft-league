import PlayerAvatar from "../../components/PlayerAvatar";
import type { BracketMatch, SlotSource } from "../../api/client";

// The playoff bracket as a tree (YGO-30): rounds left to right, each match centred between the two
// it's fed by, joined by lines. Read-only: clicking a match scrolls to its tile below, where the
// Report / Confirm / Enter actions are. The play-in, which isn't a tree (its loser can play again),
// is a small block of its own before it.
//
// Layout: a CSS grid with one row per first-round slot. A match in round r spans 2^(r-1) rows, so
// it sits centred on its feeders. Positions come from walking back from the final: a match's A
// source sits in slot 2i of the round before, its B source in slot 2i+1. A seed with a bye has no
// match there, so its slot stays empty and the lines still line up.
//
// Connectors are pseudo-elements on each cell (see .bracket-tree in styles.css): every match but
// the final draws a line out to the right and half the vertical join (down from a top slot, up
// from a bottom one); every match fed by an earlier one draws a short line in from the left.

/** `onOpen` null: a preview (before the start), whose boxes aren't clickable. */
export default function PlayoffBracket({ matches, onOpen }: { matches: BracketMatch[]; onOpen: ((id: string) => void) | null }) {
  const playIn = matches.filter((m) => m.stage === "PLAY_IN");
  const bracket = matches.filter((m) => m.stage === "PLAYOFFS");
  if (bracket.length === 0 || bracket.some((m) => !m.aSource || !m.bSource)) return null;
  const byId = new Map(matches.map((m) => [m.id, m]));
  const rounds = Math.max(...bracket.map((m) => m.round));
  const finalMatch = bracket.find((m) => m.round === rounds)!;

  // slots[r][i]: the match in round r (1-based), position i; null for a bye.
  const slots: (BracketMatch | null)[][] = Array.from({ length: rounds + 1 }, (_, r) => Array(r === 0 ? 0 : 2 ** (rounds - r)).fill(null));
  const feeder = (s: SlotSource | null) => {
    const m = s?.kind === "WINNER" && s.matchId ? byId.get(s.matchId) : undefined;
    return m?.stage === "PLAYOFFS" ? m : undefined;
  };
  const place = (m: BracketMatch, r: number, i: number) => {
    slots[r][i] = m;
    const a = feeder(m.aSource);
    const b = feeder(m.bSource);
    if (a && r > 1) place(a, r - 1, 2 * i);
    if (b && r > 1) place(b, r - 1, 2 * i + 1);
  };
  place(finalMatch, rounds, 0);
  const leaves = 2 ** (rounds - 1);

  return (
    <div className="bracket-view">
      {playIn.length > 0 && <PlayIn matches={playIn} all={matches} onOpen={onOpen} />}
      <div className="bracket-scroll">
        <div className="bracket-tree" style={{ gridTemplateColumns: `repeat(${rounds}, 12.5rem)`, gridTemplateRows: `auto repeat(${leaves}, minmax(4.6rem, auto))` }}>
          {Array.from({ length: rounds }, (_, k) => k + 1).map((r) => (
            <div key={`h${r}`} className="bracket-col-head" style={{ gridColumn: r, gridRow: 1 }}>
              {roundTitle(bracket.find((m) => m.round === r)?.label ?? `Round ${r}`)}
            </div>
          ))}
          {slots.flatMap((col, r) => col.map((m, i) => {
            if (!m) return null;
            const span = 2 ** (r - 1);
            const fed = !!feeder(m.aSource) || !!feeder(m.bSource);
            const cls = ["bracket-cell", r < rounds ? (i % 2 === 0 ? "top" : "bottom") : "final", fed ? "fed" : ""].filter(Boolean).join(" ");
            return (
              <div key={m.id} className={cls} style={{ gridColumn: r, gridRow: `${i * span + 2} / span ${span}` }}>
                <TreeMatch m={m} onOpen={onOpen} />
              </div>
            );
          }))}
        </div>
      </div>
    </div>
  );
}

function roundTitle(label: string): string {
  return label === "Final" || label.startsWith("Round of") ? label : `${label}s`;
}

/** One match box in the tree: two lines (seed, player or where they come from, games won). */
function TreeMatch({ m, onOpen }: { m: BracketMatch; onOpen: ((id: string) => void) | null }) {
  const r = m.result;
  const final = r?.status === "CONFIRMED";
  const line = (side: "a" | "b") => {
    const p = side === "a" ? m.a : m.b;
    const seed = side === "a" ? m.seedA : m.seedB;
    const src = side === "a" ? m.aSource : m.bSource;
    const games = r && !r.forfeit ? (side === "a" ? r.gamesA : r.gamesB) : r?.forfeit === (side === "a" ? "A" : "B") ? "FF" : "";
    const won = final && m.winner && p && m.winner.memberId === p.memberId;
    const lost = final && p && !won;
    return (
      <span className={`tree-side${won ? " won" : ""}${lost ? " lost" : ""}${p?.you ? " you" : ""}`}>
        <span className="tree-seed">{seed ?? (src?.kind === "SEED" ? src.seed : "")}</span>
        <span className="tree-name">
          {p ? <><PlayerAvatar name={p.name} passcode={p.avatar} size="xs" />{p.name}</> : <span className="muted">{pending(src, m)}</span>}
        </span>
        <span className="tree-games">{games}</span>
      </span>
    );
  };
  const status = !m.a || !m.b ? "" : final ? "" : r?.status === "PENDING" ? "reported" : r?.status === "DISPUTED" ? "disputed" : m.overdue ? "overdue" : "";
  return (
    <button type="button" className={`tree-match${m.mine ? " mine" : ""}${final ? " done" : ""}${onOpen ? "" : " preview"}`}
            onClick={() => onOpen?.(m.id)} tabIndex={onOpen ? undefined : -1}
            title={onOpen ? `${m.label} · best of ${m.bestOf}: open the match` : `${m.label} · best of ${m.bestOf}`}>
      {line("a")}
      {line("b")}
      {status && <span className={`tree-status ${status}`}>{status}</span>}
    </button>
  );
}

/** What fills a slot until its player is known: "Seed 5", "Play-in winner", "TBD". */
function pending(src: SlotSource | null, m: BracketMatch): string {
  if (!src) return "TBD";
  if (src.kind === "SEED") return `Seed ${src.seed}`;
  const from = src.kind === "WINNER" ? (src === m.aSource ? m.aFrom : m.bFrom) : null;
  return from?.includes("Play-in") ? "Play-in winner" : "TBD";
}

/** The play-in as a row of small boxes, each saying where its winner and loser go. */
function PlayIn({ matches, all, onOpen }: { matches: BracketMatch[]; all: BracketMatch[]; onOpen: ((id: string) => void) | null }) {
  const next = (id: string, kind: "WINNER" | "LOSER") => {
    const to = all.find((x) => (x.aSource?.matchId === id && x.aSource.kind === kind) || (x.bSource?.matchId === id && x.bSource.kind === kind));
    if (!to) return kind === "LOSER" ? "out" : "—";
    return to.stage === "PLAY_IN" ? "plays again" : `to the ${to.label.toLowerCase()}`;
  };
  return (
    <div className="play-in-block">
      <div className="bracket-col-head">Play-in</div>
      <div className="play-in-matches">
        {matches.map((m) => (
          <div key={m.id} className="play-in-match">
            <span className="play-in-label">{m.label.replace(/^Play-in: /, "")}</span>
            <TreeMatch m={m} onOpen={onOpen} />
            <span className="play-in-next">Winner {next(m.id, "WINNER")} · loser {next(m.id, "LOSER")}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
