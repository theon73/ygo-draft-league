import InfoTip from "../../components/InfoTip";
import { useEffect, useState } from "react";
import { apiPut, type BanListOption, type SeasonRules, type Tiebreaker } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { currentWeekNote } from "./currentWeek";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";
import { Loading } from "../../components/Feedback";

const dayLabel = (d: string) => d.charAt(0) + d.slice(1).toLowerCase();
// The same description the server gives (PlayoffService.shape), live while editing.
function playoffShape(direct: number, playIn: number): string {
  if (!direct || direct < 1) return "Set how many teams qualify directly.";
  const bracket = direct + playIn / 2;
  if (bracket < 2) return "The bracket needs at least two teams.";
  let size = 1;
  while (size < bracket) size *= 2;
  const byes = size - bracket;
  let s = direct === 1 ? "Seed 1 qualifies directly" : `Seeds 1-${direct} qualify directly`;
  if (playIn === 2) s += `; ${direct + 1} v ${direct + 2} play in for 1 spot`;
  if (playIn === 4) s += `; ${direct + 1}-${direct + 4} play in for 2 spots (the winner of ${direct + 1} v ${direct + 2} is in, its loser plays the winner of ${direct + 3} v ${direct + 4} for the last spot)`;
  s += `; ${bracket} in the bracket`;
  if (byes === 1) s += ", seed 1 has a bye";
  else if (byes > 1) s += `, seeds 1${byes === 2 ? " and 2" : `-${byes}`} have byes`;
  return s + ".";
}

/** Today's date (YYYY-MM-DD) in a time zone. */
const today = (zone: string) => new Intl.DateTimeFormat("en-CA", { timeZone: zone }).format(new Date());

const TIEBREAKERS: Record<Tiebreaker, string> = { GAME_DIFF: "Game difference", GAMES_WON: "Games won", HEAD_TO_HEAD: "Head-to-head" };

export default function SeasonRulesPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const rules = useApi<SeasonRules>(`${base}/rules`);
  const banLists = useApi<BanListOption[]>(`/leagues/${league.id}/ban-lists`);

  if (rules.error) return <p className="bad">Couldn't load the rules ({rules.error})</p>;
  if (!rules.data) return <Loading />;
  return <RulesForm key={rules.data.version ?? 0} rules={rules.data} banLists={banLists.data ?? []} base={base} onSaved={rules.reload} />;
}

function RulesForm({ rules, banLists, base, onSaved }: { rules: SeasonRules; banLists: BanListOption[]; base: string; onSaved: () => void }) {
  const edit = rules.canManage;
  const [startDate, setStartDate] = useState(rules.calendar.startDate ?? "");
  // A new season starts from sensible defaults (round-robin, each pair once, best of 3), so saving
  // the form gives a playable season; the format is only prefilled before the season starts.
  const [format, setFormat] = useState(rules.competition.format ?? (rules.started ? "" : "ROUND_ROBIN"));
  const [cycles, setCycles] = useState(rules.competition.roundRobinCycles?.toString() ?? (rules.started ? "" : "1"));
  const [bestOf, setBestOf] = useState(String(rules.competition.matchBestOf ?? rules.competition.bestOf));
  // Only kept for formats with an explicit week count; a round-robin's weeks are derived.
  const [weeks, setWeeks] = useState(rules.calendar.weeksDerived ? "" : rules.calendar.regularWeeks?.toString() ?? "");
  const roundRobin = format === "ROUND_ROBIN";
  const [cutoff, setCutoff] = useState(rules.format.tcgCutoff ?? "");
  const [banList, setBanList] = useState(rules.format.banList?.revisionId.toString() ?? "");
  const [faPerWeek, setFaPerWeek] = useState(rules.transactions.freeAgencyPerWeek?.toString() ?? "");
  const [tradesPerWeek, setTradesPerWeek] = useState(rules.transactions.tradesPerWeek?.toString() ?? "");
  const [deadline, setDeadline] = useState(rules.transactions.tradeDeadlineWeek?.toString() ?? "");
  const [approval, setApproval] = useState(rules.transactions.commissionerApproval);
  const [confirmHours, setConfirmHours] = useState(String(rules.results.autoConfirmHours ?? rules.results.confirmHours));
  const [tiebreakers, setTiebreakers] = useState<Tiebreaker[]>(rules.results.tiebreakers);
  const po = rules.playoffs;
  const [poOn, setPoOn] = useState(po.enabled);
  const [direct, setDirect] = useState(po.directQualifiers?.toString() ?? "");
  const [playIn, setPlayIn] = useState(String(po.playInTeams ?? 0));
  // Best-ofs follow the regular season unless a box is ticked (then the input appears).
  const [poOwnBo, setPoOwnBo] = useState(po.bestOf !== null);
  const [poBo, setPoBo] = useState(String(po.bestOf ?? rules.competition.bestOf));
  const [piOwnBo, setPiOwnBo] = useState(po.playInBestOf !== null);
  const [piBo, setPiBo] = useState(String(po.playInBestOf ?? po.bestOf ?? rules.competition.bestOf));
  const [fromWeek, setFromWeek] = useState(rules.minEffectiveWeek.toString());
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  useEffect(() => setMessage(null), [startDate, weeks, cutoff, banList, fromWeek, format, cycles, bestOf, faPerWeek, tradesPerWeek, deadline, approval, confirmHours, tiebreakers, poOn, direct, playIn, poOwnBo, poBo, piOwnBo, piBo]);

  const save = async () => {
    try {
      await apiPut(`${base}/rules`, {
        startDate: startDate || null,
        regularWeeks: !roundRobin && weeks ? Number(weeks) : null,
        competitionFormat: format || null,
        roundRobinCycles: roundRobin && cycles ? Number(cycles) : null,
        matchBestOf: Number(bestOf),
        // An empty cutoff means "today" (in the league's zone): every card released so far.
        tcgCutoff: cutoff || today(rules.calendar.timeZone),
        banListRevisionId: banList ? Number(banList) : null,
        freeAgencyPerWeek: faPerWeek === "" ? null : Number(faPerWeek),
        tradesPerWeek: tradesPerWeek === "" ? null : Number(tradesPerWeek),
        tradeDeadlineWeek: deadline === "" ? null : Number(deadline),
        commissionerApproval: approval,
        autoConfirmHours: confirmHours === "" ? null : Number(confirmHours),
        tiebreakers,
        playoffsEnabled: poOn,
        playoffDirectQualifiers: direct ? Number(direct) : null,
        playInTeams: Number(playIn),
        playoffBestOf: poOn && poOwnBo ? Number(poBo) : null,
        playInBestOf: poOn && Number(playIn) > 0 && piOwnBo ? Number(piBo) : null,
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
        <label><span className="label-text">Format <InfoTip>
          Round-robin: every pair of players meets the chosen number of times, one round per week. The rounds follow the player
          list until the season starts. Swiss, weekly head-to-head and ladder formats come later.
        </InfoTip></span>
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
        <label>Matches are best of
          {/* Unlike the format, this can change mid-season (from the week you choose below). */}
          <select value={bestOf} onChange={(e) => setBestOf(e.target.value)} disabled={!edit}>
            {[1, 3, 5, 7, 9].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
      </div>
      {rules.competition.format === "ROUND_ROBIN" && rules.competition.rounds !== null && (
        <p className="muted">
          {rules.competition.players < 2
            ? "Add at least two players on the Players tab to lay out the rounds."
            : `${rules.competition.players} players: ${rules.competition.rounds} rounds, one per week, so ${rules.competition.matchesPerPlayer} matches each` +
              (rules.competition.byes ? "; with an odd number, one player has a bye each round." : ".")}
        </p>
      )}

      <h4>Calendar</h4>
      <div className="form-grid">
        <label><span className="label-text">Start date <InfoTip>
          Week 1 begins at the first week start on or after this date. Weeks start {dayLabel(rules.calendar.weekStartsOn)} {rules.calendar.weekStartsAt}
          {" "}({rules.calendar.timeZone.replaceAll("_", " ")}){rules.calendar.weekStartLocked ? ", locked in when the season began." : ", from the league settings; that's locked in when the season begins."}
        </InfoTip></span>
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
        <label><span className="label-text">TCG cutoff <InfoTip>
          Cards released in the TCG on or before the cutoff make up the dated pool; add exceptions on top of it on the Pool tab.
          Left empty, it's set to today when you save.
        </InfoTip></span>
          <input type="date" value={cutoff} onChange={(e) => setCutoff(e.target.value)} disabled={!edit} />
        </label>
        <label><span className="label-text">Ban list <InfoTip>A season keeps the exact revision you pick, even if the list is updated later.</InfoTip></span>
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
      {pinnedIsOld && pinned && (
        <p className="muted small">A newer revision of {pinned.name} (revision {pinned.latestRevision}) exists; pick it from the list to switch.</p>
      )}

      <h4>Transactions <InfoTip>
        Limits reset when each week starts; the time between the draft and week 1 counts as week 1. This week's claims and trades
        can themselves be traded.
      </InfoTip></h4>
      <div className="form-grid">
        <label><span className="label-text">Free-agent claims per week <InfoTip>
          A claim picks up a free agent and drops a card from the roster, so rosters keep their size. Blank means unlimited; 0 turns claims off.
        </InfoTip></span>
          <input type="number" min={0} max={50} value={faPerWeek} onChange={(e) => setFaPerWeek(e.target.value)} disabled={!edit}
                 placeholder="Unlimited" />
        </label>
        <label><span className="label-text">Trades per week <InfoTip>
          A trade counts for both players, and must be even: the same number of cards each way. Blank means unlimited.
        </InfoTip></span>
          <input type="number" min={0} max={50} value={tradesPerWeek} onChange={(e) => setTradesPerWeek(e.target.value)} disabled={!edit}
                 placeholder="Unlimited" />
        </label>
        <label>Trade deadline (last week)
          <input type="number" min={0} max={52} value={deadline} onChange={(e) => setDeadline(e.target.value)} disabled={!edit} placeholder="None" />
        </label>
      </div>
      <label className="check">
        <input type="checkbox" checked={approval} onChange={(e) => setApproval(e.target.checked)} disabled={!edit} />{" "}
        Commissioners approve accepted trades
      </label>

      <h4>Results and standings</h4>
      <div className="form-grid">
        <label><span className="label-text">Unanswered reports confirm after (hours) <InfoTip>
          Either player reports a match and the other confirms it; a report nobody answers becomes final after this delay.
        </InfoTip></span>
          <input type="number" min={1} max={336} value={confirmHours} onChange={(e) => setConfirmHours(e.target.value)} disabled={!edit}
                 placeholder="48" />
        </label>
      </div>
      <p>Standings rank by wins, then:</p>
      <ol className="tiebreakers">
        {tiebreakers.map((t, i) => (
          <li key={t}>
            <span className="tiebreaker-name">{TIEBREAKERS[t]}</span>
            {edit && (
              <span className="tiebreaker-moves">
                <button type="button" aria-label={`Move ${TIEBREAKERS[t]} up`} disabled={i === 0}
                        onClick={() => setTiebreakers((x) => { const y = [...x]; [y[i - 1], y[i]] = [y[i], y[i - 1]]; return y; })}>↑</button>
                <button type="button" aria-label={`Move ${TIEBREAKERS[t]} down`} disabled={i === tiebreakers.length - 1}
                        onClick={() => setTiebreakers((x) => { const y = [...x]; [y[i], y[i + 1]] = [y[i + 1], y[i]]; return y; })}>↓</button>
              </span>
            )}
          </li>
        ))}
      </ol>

      <h4>Playoffs</h4>
      {rules.playoffsStarted && <p className="muted">The playoffs have started, so these settings are fixed.</p>}
      <label className="check">
        <input type="checkbox" checked={poOn} onChange={(e) => setPoOn(e.target.checked)} disabled={!edit || rules.playoffsStarted} /> This season has playoffs
        <InfoTip>Without playoffs, the regular-season standings decide the champion.</InfoTip>
      </label>
      {poOn && (
        <>
          <div className="form-grid">
            <label>Teams that qualify directly
              <input type="number" min={1} max={64} value={direct} onChange={(e) => setDirect(e.target.value)} disabled={!edit || rules.playoffsStarted} />
            </label>
            <label>Play-in
              <select value={playIn} onChange={(e) => setPlayIn(e.target.value)} disabled={!edit || rules.playoffsStarted}>
                <option value="0">None</option>
                <option value="2">2 teams: one match for one spot</option>
                <option value="4">4 teams: two spots</option>
              </select>
            </label>
          </div>
          <p className="muted">{playoffShape(Number(direct), Number(playIn))}</p>
          <label className="check">
            <input type="checkbox" checked={poOwnBo} onChange={(e) => setPoOwnBo(e.target.checked)} disabled={!edit || rules.playoffsStarted} /> Playoff matches use a different best-of
            {!poOwnBo && <span className="muted"> (now best of {rules.competition.bestOf}, like the regular season)</span>}
          </label>
          {poOwnBo && (
            <label className="inline-label indent">Playoff matches are best of{" "}
              <select value={poBo} onChange={(e) => setPoBo(e.target.value)} disabled={!edit || rules.playoffsStarted}>{[1, 3, 5, 7, 9].map((n) => <option key={n} value={n}>{n}</option>)}</select>
            </label>
          )}
          {Number(playIn) > 0 && (
            <>
              <label className="check">
                <input type="checkbox" checked={piOwnBo} onChange={(e) => setPiOwnBo(e.target.checked)} disabled={!edit || rules.playoffsStarted} /> Play-in matches use a different best-of
                {!piOwnBo && <span className="muted"> (now the same as the playoffs)</span>}
              </label>
              {piOwnBo && (
                <label className="inline-label indent">Play-in matches are best of{" "}
                  <select value={piBo} onChange={(e) => setPiBo(e.target.value)} disabled={!edit || rules.playoffsStarted}>{[1, 3, 5, 7, 9].map((n) => <option key={n} value={n}>{n}</option>)}</select>
                </label>
              )}
            </>
          )}
        </>
      )}

      {edit && (
        <p>
          {rules.started && (
            <label className="inline-label">Takes effect from week{" "}
              <input type="number" className="narrow-num" min={rules.minEffectiveWeek} value={fromWeek} onChange={(e) => setFromWeek(e.target.value)} />
            </label>
          )}{" "}
          {rules.started && <span className="muted">{currentWeekNote(rules.currentWeek, rules.calendar.regularWeeks)}</span>}{" "}
          <button className="primary" onClick={() => void save()}>Save rules</button>
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
