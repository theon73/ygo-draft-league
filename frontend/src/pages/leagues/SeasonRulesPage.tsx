import { useEffect, useState } from "react";
import { apiPut, type BanListOption, type SeasonRules } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";

const dayLabel = (d: string) => d.charAt(0) + d.slice(1).toLowerCase();

export default function SeasonRulesPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const rules = useApi<SeasonRules>(`${base}/rules`);
  const banLists = useApi<BanListOption[]>(`/leagues/${league.id}/ban-lists`);

  if (rules.error) return <p className="bad">Couldn't load the rules ({rules.error})</p>;
  if (!rules.data) return <p className="muted">Loading…</p>;
  return <RulesForm key={rules.data.version ?? 0} rules={rules.data} banLists={banLists.data ?? []} base={base} onSaved={rules.reload} />;
}

function RulesForm({ rules, banLists, base, onSaved }: { rules: SeasonRules; banLists: BanListOption[]; base: string; onSaved: () => void }) {
  const edit = rules.canManage;
  const [startDate, setStartDate] = useState(rules.calendar.startDate ?? "");
  const [format, setFormat] = useState(rules.competition.format ?? "");
  const [cycles, setCycles] = useState(rules.competition.roundRobinCycles?.toString() ?? "");
  // Only kept for formats with an explicit week count; a round-robin's weeks are derived.
  const [weeks, setWeeks] = useState(rules.calendar.weeksDerived ? "" : rules.calendar.regularWeeks?.toString() ?? "");
  const roundRobin = format === "ROUND_ROBIN";
  const [cutoff, setCutoff] = useState(rules.format.tcgCutoff ?? "");
  const [banList, setBanList] = useState(rules.format.banList?.revisionId.toString() ?? "");
  const [faPerWeek, setFaPerWeek] = useState(rules.transactions.freeAgencyPerWeek?.toString() ?? "");
  const [fromWeek, setFromWeek] = useState(rules.minEffectiveWeek.toString());
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  useEffect(() => setMessage(null), [startDate, weeks, cutoff, banList, fromWeek, format, cycles, faPerWeek]);

  const save = async () => {
    try {
      await apiPut(`${base}/rules`, {
        startDate: startDate || null,
        regularWeeks: !roundRobin && weeks ? Number(weeks) : null,
        competitionFormat: format || null,
        roundRobinCycles: roundRobin && cycles ? Number(cycles) : null,
        tcgCutoff: cutoff || null,
        banListRevisionId: banList ? Number(banList) : null,
        freeAgencyPerWeek: faPerWeek === "" ? null : Number(faPerWeek),
        ...(rules.started && { effectiveFromWeek: Number(fromWeek) }),
      });
      onSaved();
    } catch (e) {
      setMessage({ ok: false, text: (e as Error).message });
    }
  };

  // Picking a list pins its newest revision. If the season is pinned to an older revision, keep
  // that as its own option so the form doesn't silently move it.
  const pinned = rules.format.banList;
  const pinnedIsOld = pinned && pinned.revisionId !== pinned.latestRevisionId;
  const fmtWeek = (iso: string) =>
    new Date(iso).toLocaleString(undefined, { timeZone: rules.calendar.timeZone, weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });

  return (
    <>
      {rules.started && (
        <p className="muted">
          The season has started: changes take effect from a week you choose (never earlier than week {rules.minEffectiveWeek}), and the
          calendar is fixed.
        </p>
      )}

      <h4>Competition</h4>
      <div className="form-grid">
        <label>Format
          <select value={format} onChange={(e) => setFormat(e.target.value)} disabled={!edit || rules.started}>
            <option value="">Not chosen</option>
            <option value="ROUND_ROBIN">Round-robin</option>
          </select>
        </label>
        {roundRobin && (
          <label>Each pair plays
            <span>
              <input type="number" className="narrow-num" min={1} max={10} value={cycles} onChange={(e) => setCycles(e.target.value)}
                     disabled={!edit || rules.started} /> time{cycles === "1" ? "" : "s"}
            </span>
          </label>
        )}
      </div>
      {rules.competition.format === "ROUND_ROBIN" && rules.competition.rounds !== null && (
        <p className="muted">
          {rules.competition.players < 2
            ? "Add at least two players on the Setup tab to lay out the rounds."
            : `${rules.competition.players} players: ${rules.competition.rounds} rounds, one per week, so ${rules.competition.matchesPerPlayer} matches each` +
              (rules.competition.byes ? ". With an odd number of players, one player has a bye each round." : ".") +
              (rules.started ? "" : " This follows the player list until the season starts.")}
        </p>
      )}
      <p className="muted">Swiss, weekly head-to-head and ladder formats come later.</p>

      <h4>Calendar</h4>
      <div className="form-grid">
        <label>Start date
          <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} disabled={!edit || rules.started} />
        </label>
        {roundRobin ? (
          <label>Regular-season weeks
            <span className="derived">{rules.calendar.weeksDerived && rules.calendar.regularWeeks ? rules.calendar.regularWeeks : "—"} (from the round-robin)</span>
          </label>
        ) : (
          <label>Regular-season weeks
            <input type="number" min={1} max={52} value={weeks} onChange={(e) => setWeeks(e.target.value)} disabled={!edit || rules.started} />
          </label>
        )}
      </div>
      <p className="muted">
        Weeks start {dayLabel(rules.calendar.weekStartsOn)} {rules.calendar.weekStartsAt} ({rules.calendar.timeZone.replaceAll("_", " ")})
        {rules.calendar.weekStartLocked ? ", locked in when the season began." : ", from the league settings (locked in when the season begins)."}
        {" "}Week 1 begins at the first week start on or after the start date.
      </p>
      {rules.weeks.length > 0 && (
        <details>
          <summary>
            {rules.weeks.length} weeks: {fmtWeek(rules.weeks[0].start)} → {fmtWeek(rules.weeks[rules.weeks.length - 1].end)}
            {rules.currentWeek !== null && rules.currentWeek > 0 && rules.currentWeek <= rules.weeks.length && ` · now week ${rules.currentWeek}`}
          </summary>
          <ol className="weeks">
            {rules.weeks.map((w) => (
              <li key={w.number} className={w.number === rules.currentWeek ? "current" : undefined}>
                {fmtWeek(w.start)} → {fmtWeek(w.end)}
              </li>
            ))}
          </ol>
        </details>
      )}

      <h4>Format</h4>
      <div className="form-grid">
        <label>TCG cutoff
          <input type="date" value={cutoff} onChange={(e) => setCutoff(e.target.value)} disabled={!edit} />
        </label>
        <label>Ban list
          <select value={banList} onChange={(e) => setBanList(e.target.value)} disabled={!edit}>
            <option value="">None</option>
            {pinnedIsOld && pinned && (
              <option value={pinned.revisionId}>{pinned.name} (revision {pinned.revision}, current)</option>
            )}
            {banLists.map((b) => (
              <option key={b.listId} value={b.latestRevisionId}>
                {b.name} ({b.official ? "official" : "league list"}, revision {b.latestRevision})
              </option>
            ))}
          </select>
        </label>
      </div>
      <p className="muted">
        Cards released in the TCG on or before the cutoff make up the dated pool; you can add exceptions on top of it (coming with the card
        pool). A season keeps the exact ban list revision you pick, even if the list is updated later.
        {pinnedIsOld && pinned && ` A newer revision of ${pinned.name} (revision ${pinned.latestRevision}) exists; pick it from the list to switch.`}
      </p>

      <h4>Transactions</h4>
      <div className="form-grid">
        <label>Free-agent claims per week
          <input type="number" min={0} max={50} value={faPerWeek} onChange={(e) => setFaPerWeek(e.target.value)} disabled={!edit}
                 placeholder="Unlimited" />
        </label>
      </div>
      <p className="muted">
        A claim picks up a free agent and drops a card from the roster, so rosters keep their size. Blank means unlimited, 0 turns
        claims off. Limits reset when each week starts; the time between the draft and week 1 counts as week 1.
      </p>

      {edit && (
        <p>
          {rules.started && (
            <label className="inline-label">Takes effect from week{" "}
              <input type="number" className="narrow-num" min={rules.minEffectiveWeek} value={fromWeek} onChange={(e) => setFromWeek(e.target.value)} />
            </label>
          )}{" "}
          <button onClick={() => void save()}>Save rules</button>
        </p>
      )}
      {message && <p className={message.ok ? "good" : "bad"}>{message.text}</p>}

      {rules.history.length > 0 && (
        <details>
          <summary>History ({rules.history.length} version{rules.history.length === 1 ? "" : "s"})</summary>
          <ul>
            {rules.history.map((h) => (
              <li key={h.version}>
                Version {h.version}, in effect from {h.effectiveFromWeek === 0 ? "the start" : `week ${h.effectiveFromWeek}`}: {h.by ?? "system"},{" "}
                {new Date(h.at).toLocaleString()}
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  );
}
