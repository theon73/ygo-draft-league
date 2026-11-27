import { useState } from "react";
import { apiPatch, apiPost, apiPut, type MyPings, type NotificationEvent, type NotificationSettings, type Weekday } from "../../api/client";
import { useApi } from "../../hooks/useApi";
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

  // Every member gets their own Discord pings; the league settings are the owner's.
  if (league.myRole !== "OWNER") {
    return (
      <>
        <h3>Settings</h3>
        <MyDiscordPings leagueId={league.id} />
        <p className="muted">Only the league owner can change the league's settings.</p>
      </>
    );
  }

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

      <DiscordSettings leagueId={league.id} />
      <MyDiscordPings leagueId={league.id} />
    </>
  );
}

// A member's own pings: their Discord user id (entered by hand; the same for all their leagues) and
// whether this league's "on the clock" messages should @mention them. Discord only pings by id, so
// a typed @name wouldn't work.
function MyDiscordPings({ leagueId }: { leagueId: string }) {
  const base = `/leagues/${leagueId}/notifications/me`;
  const mine = useApi<MyPings>(base);
  const [id, setId] = useState<string | null>(null);   // null = not edited yet: show the saved id
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  if (!mine.data) return mine.error ? <p className="bad">Couldn't load your Discord settings ({mine.error})</p> : null;
  const m = mine.data;
  const shown = id ?? m.discordUserId ?? "";

  const save = async (body: { discordUserId?: string; ping?: boolean }, done: string) => {
    setMessage(null);
    try {
      await apiPut(base, body);
      setId(null);
      setMessage({ ok: true, text: done });
    } catch (e) {
      setMessage({ ok: false, text: (e as Error).message });
    } finally {
      mine.reload();
    }
  };

  return (
    <>
      <h4>My Discord pings</h4>
      <p className="muted">
        Get @mentioned in this league's Discord when you're on the clock. Discord can only ping you by your user ID: turn on
        Developer Mode (User Settings → Advanced), then right-click your name and choose Copy User ID. It's saved for all your
        leagues; you choose per league whether to be pinged.
        {!m.leaguePostsOnTheClock && " This league doesn't post \"on the clock\" messages at the moment, so there's nothing to ping you with yet."}
      </p>
      <p>
        <input value={shown} onChange={(e) => setId(e.target.value)} inputMode="numeric" placeholder="e.g. 284104569123456789"
               aria-label="Your Discord user ID" />{" "}
        <button disabled={id === null || id.trim() === (m.discordUserId ?? "")}
                onClick={() => void save({ discordUserId: (id ?? "").trim() }, (id ?? "").trim() ? "Discord user ID saved." : "Discord user ID removed.")}>
          Save ID
        </button>
      </p>
      <label className="check">
        <input type="checkbox" checked={m.ping} disabled={!m.discordUserId}
               onChange={(e) => void save({ ping: e.target.checked }, e.target.checked ? "You'll be pinged in this league." : "No more pings in this league.")} />{" "}
        Ping me in this league when I'm on the clock {!m.discordUserId && <span className="muted">(save your Discord user ID first)</span>}
      </label>
      {message && <p className={message.ok ? "good" : "bad"}>{message.text}</p>}
    </>
  );
}

const EVENTS: { value: NotificationEvent; label: string; hint: string }[] = [
  { value: "DRAFT_PICKS", label: "Draft news", hint: "start and finish, every pick (auto-picks, skips, make-ups), undos, pauses, timer changes" },
  { value: "DRAFT_ON_THE_CLOCK", label: "On the clock", hint: "who's up next, with a countdown Discord shows in everyone's own time zone" },
  { value: "TRANSACTIONS", label: "Transactions", hint: "free-agent claims and what was dropped (trades too, once they exist)" },
];

// The league's Discord webhook. The saved URL never comes back from the server (anyone holding it
// can post to the channel), so the box is for a new URL only; the hint shows which one is saved.
function DiscordSettings({ leagueId }: { leagueId: string }) {
  const base = `/leagues/${leagueId}/notifications`;
  const settings = useApi<NotificationSettings>(base);
  const [url, setUrl] = useState("");
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  if (!settings.data) return settings.error ? <p className="bad">Couldn't load notification settings ({settings.error})</p> : null;
  const s = settings.data;

  const act = async (change: () => Promise<unknown>, done: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await change();
      setMessage({ ok: true, text: done });
      setUrl("");
    } catch (e) {
      setMessage({ ok: false, text: (e as Error).message });
    } finally {
      setBusy(false);
      settings.reload();
    }
  };
  const toggle = (event: NotificationEvent, on: boolean) => {
    const events = on ? [...s.events, event] : s.events.filter((e) => e !== event);
    void act(() => apiPut(base, { events }), "Saved.");
  };

  return (
    <>
      <h4>Discord</h4>
      <p className="muted">
        Post draft news and transactions to a Discord channel. In Discord: Server Settings → Integrations → Webhooks → New Webhook, pick the
        channel, then Copy Webhook URL and paste it here.
      </p>
      <p>
        {s.webhookSet ? <>A webhook is set (ending <code>{s.webhookHint}</code>).</> : "No webhook yet."}
      </p>
      <p>
        <input type="password" value={url} onChange={(e) => setUrl(e.target.value)} autoComplete="off" className="wide"
               placeholder={s.webhookSet ? "Paste a new webhook URL to replace it" : "https://discord.com/api/webhooks/…"} aria-label="Discord webhook URL" />{" "}
        <button disabled={busy || !url.trim()}
                onClick={() => void act(() => apiPut(base, { webhookUrl: url.trim(), ...(s.webhookSet ? {} : { events: EVENTS.map((e) => e.value) }) }), "Webhook saved.")}>
          Save webhook
        </button>
      </p>
      {s.webhookSet && (
        <>
          {EVENTS.map((e) => (
            <label key={e.value} className="check">
              <input type="checkbox" checked={s.events.includes(e.value)} disabled={busy} onChange={(ev) => toggle(e.value, ev.target.checked)} />{" "}
              {e.label} <span className="muted">({e.hint})</span>
            </label>
          ))}
          <p>
            <button disabled={busy} onClick={() => void act(() => apiPost(`${base}/test`), "Test message sent. Check the channel.")}>Send test message</button>{" "}
            <button className="link" disabled={busy}
                    onClick={() => { if (window.confirm("Remove the webhook? Nothing will be posted to Discord.")) void act(() => apiPut(base, { webhookUrl: "" }), "Webhook removed."); }}>
              remove webhook
            </button>
          </p>
          <p className="muted">Trades, free agency and results will be added here when those features arrive.</p>
        </>
      )}
      {message && <p className={message.ok ? "good" : "bad"}>{message.text}</p>}
    </>
  );
}
