import { useState } from "react";
import { apiPatch, type Weekday } from "../../api/client";
import { useLeague } from "./LeagueLayout";

// Every IANA zone the browser knows, e.g. "America/Chicago".
const ZONES: string[] = Intl.supportedValuesOf("timeZone");
const WEEKDAYS: Weekday[] = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const dayLabel = (d: Weekday) => d.charAt(0) + d.slice(1).toLowerCase();

export default function LeagueSettings() {
  const { league, reload } = useLeague();
  const [zone, setZone] = useState(league.timeZone);
  const [day, setDay] = useState<Weekday>(league.weekStartsOn);
  const [time, setTime] = useState(league.weekStartsAt);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  if (league.myRole !== "OWNER") return <p className="muted">Only the league owner can change league settings.</p>;

  const changed = zone !== league.timeZone || day !== league.weekStartsOn || time !== league.weekStartsAt;
  const save = async () => {
    setMessage(null);
    try {
      await apiPatch(`/leagues/${league.id}`, { timeZone: zone, weekStartsOn: day, weekStartsAt: time });
      reload();
      setMessage({ ok: true, text: "Saved." });
    } catch (e) {
      setMessage({ ok: false, text: (e as Error).message });
    }
  };

  // Shown in the league's zone (the saved one), whatever the viewer's own zone is.
  const fmt = (iso: string) =>
    new Date(iso).toLocaleString(undefined, { timeZone: league.timeZone, weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  const now = new Date().toLocaleString(undefined, { timeZone: zone, weekday: "short", hour: "numeric", minute: "2-digit" });

  return (
    <>
      <h3>Settings</h3>

      <h4>Time zone</h4>
      <p className="muted">Weeks, deadlines and draft times are in this zone. It's currently {now} there.</p>
      <select value={zone} onChange={(e) => setZone(e.target.value)} aria-label="League time zone">
        {(ZONES.includes(league.timeZone) ? ZONES : [league.timeZone, ...ZONES]).map((z) => (
          <option key={z} value={z}>{z.replaceAll("_", " ")}</option>
        ))}
      </select>

      <h4>Week starts</h4>
      <p className="muted">
        Each league week (weekly trade and free-agent limits, match deadlines, "this week's matchups") runs from this moment to
        the same moment a week later. Seasons will lock in the week start when they begin, so changing it later won't reshuffle
        a running season.
      </p>
      <select value={day} onChange={(e) => setDay(e.target.value as Weekday)} aria-label="Week starts on">
        {WEEKDAYS.map((d) => <option key={d} value={d}>{dayLabel(d)}</option>)}
      </select>{" "}
      at{" "}
      <input type="time" value={time} onChange={(e) => setTime(e.target.value)} aria-label="Week starts at" required />
      <p className="muted">
        This week (as saved): {fmt(league.currentWeek.start)} → {fmt(league.currentWeek.end)} ({league.timeZone.replaceAll("_", " ")}).
      </p>

      <p><button onClick={() => void save()} disabled={!changed || !time}>Save settings</button></p>
      {message && <p className={message.ok ? "good" : "bad"}>{message.text}</p>}
    </>
  );
}
