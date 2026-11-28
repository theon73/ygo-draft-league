import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiPatch, apiPost, apiPut, type InvitePreview, type LeagueDetail, type SeasonDetail } from "../api/client";
import AvatarPicker from "../components/AvatarPicker";
import PlayerAvatar from "../components/PlayerAvatar";
import { MyDiscordPings } from "./leagues/LeagueSettings";
import { useSession } from "../auth/session";
import { useApi } from "../hooks/useApi";
import { Loading } from "../components/Feedback";

// Google sign-in leaves the app and comes back to "/", which would lose the invite. So before
// sending someone to Google we remember the invite code in sessionStorage (per browser tab), and
// PendingInviteRedirect brings them back here after they sign in.
const PENDING_KEY = "pendingInvite";

export function PendingInviteRedirect() {
  const { session } = useSession();
  const navigate = useNavigate();
  useEffect(() => {
    if (!session?.user) return;
    let code: string | null = null;
    try {
      code = sessionStorage.getItem(PENDING_KEY);
      sessionStorage.removeItem(PENDING_KEY);
    } catch {
      /* storage unavailable: nothing to resume */
    }
    if (code) navigate(`/invite/${code}`, { replace: true });
  }, [session?.user, navigate]);
  return null;
}

const STATUS_TEXT = {
  EXPIRED: "This invite has expired. Ask the league owner for a new link.",
  REVOKED: "This invite was revoked. Ask the league owner for a new link.",
  USED_UP: "This invite has already been used.",
} as const;

export default function InvitePage() {
  const { code = "" } = useParams();
  const { session } = useSession();
  const user = session?.user;
  const preview = useApi<InvitePreview>(`/invites/${encodeURIComponent(code)}`, user?.identity ?? "signed-out");
  const [error, setError] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);
  const [joined, setJoined] = useState<string | null>(null); // the league joined: show the welcome

  if (joined) return <Welcome leagueId={joined} />;
  if (preview.status === 404) {
    return (
      <section>
        <h2>Invite not found</h2>
        <p>This invite link isn't valid. Check you copied all of it, or ask the league owner for a new one.</p>
      </section>
    );
  }
  if (preview.error) return <p className="bad">Couldn't load the invite ({preview.error})</p>;
  if (!preview.data || !session) return <Loading />;

  const p = preview.data;
  const what = p.kind === "CLAIM" ? `claim the seat "${p.seatName}" in` : "join";

  const accept = async () => {
    setJoining(true);
    setError(null);
    try {
      const { leagueId } = await apiPost<{ leagueId: string }>(`/invites/${encodeURIComponent(code)}/accept`);
      setJoined(leagueId);
    } catch (e) {
      setError((e as Error).message);
      setJoining(false);
    }
  };

  const rememberInvite = () => {
    try {
      sessionStorage.setItem(PENDING_KEY, code);
    } catch {
      /* storage unavailable: they'll just need to reopen the link after signing in */
    }
  };

  return (
    <section>
      <h2>You're invited to {p.leagueName}</h2>
      {p.status !== "VALID" ? (
        <p className="bad">{STATUS_TEXT[p.status]}</p>
      ) : p.alreadyMember ? (
        <p>You're already in this league. <Link to="/leagues">Go to my leagues</Link></p>
      ) : !user ? (
        <>
          <p>Sign in to {what} {p.leagueName}.</p>
          {session.providers.includes("google") && (
            <a className="button" href="/api/oauth2/authorization/google" onClick={rememberInvite}>Sign in with Google</a>
          )}
          {session.devLogin && <p className="muted">Or use "Dev: log in as…" in the header.</p>}
        </>
      ) : (
        <>
          <p>Accept to {what} {p.leagueName}, as {user.displayName}.</p>
          <button className="primary" onClick={() => void accept()} disabled={joining}>Accept invite</button>
        </>
      )}
      {error && <p className="bad">{error}</p>}
    </section>
  );
}

// After joining (YGO-32): a short, optional welcome: an avatar, a team name for the season you're
// in, and Discord pings. Everything here can be done later, so it's all skippable.
function Welcome({ leagueId }: { leagueId: string }) {
  const { session, refresh } = useSession();
  const navigate = useNavigate();
  const league = useApi<LeagueDetail>(`/leagues/${leagueId}`);
  const seasonId = league.data?.currentSeasonId;
  const season = useApi<SeasonDetail>(seasonId ? `/leagues/${leagueId}/seasons/${seasonId}` : null);
  const [picking, setPicking] = useState(false);
  const [team, setTeam] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const user = session?.user;
  if (!league.data || !user) return <Loading />;
  const me = season.data?.participants.find((p) => p.you);

  const saveTeam = async () => {
    if (!me || !season.data) return;
    setError(null);
    try {
      await apiPatch(`/leagues/${leagueId}/seasons/${season.data.id}/participants/${me.memberId}`, { teamName: team ?? "" });
      setSaved(true);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <section className="welcome">
      <h2>Welcome to {league.data.name}!</h2>
      <p className="muted">A few optional touches. You can skip them and change any of it later.</p>

      <div className="panel welcome-step">
        <PlayerAvatar name={user.displayName} passcode={user.avatar} size="lg" />
        <div className="grow">
          <h5>Your avatar</h5>
          <p className="muted small">Pick any card's art. It shows next to your name on rosters, matches and standings.</p>
        </div>
        <button type="button" onClick={() => setPicking(true)}>{user.avatar ? "Change" : "Choose a card"}</button>
      </div>
      {picking && (
        <AvatarPicker name={user.displayName} current={user.avatar} onClose={() => setPicking(false)}
                      scopes={[{ scope: "profile", label: "Everywhere (your profile)" }]}
                      onSave={async (choice) => { await apiPut("/me/avatar", choice ?? { cardId: null }); await refresh(); }} />
      )}

      {me && season.data && (
        <div className="panel welcome-step">
          <div className="grow">
            <h5>Your team name in {season.data.name}</h5>
            <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void saveTeam(); }}>
              <input value={team ?? me.teamName ?? ""} onChange={(e) => { setTeam(e.target.value); setSaved(false); }} placeholder="Optional" maxLength={40} />
              <button type="submit" disabled={team === null}>Save</button>
              {saved && <span className="good small">Saved.</span>}
            </form>
            {error && <p className="bad">{error}</p>}
          </div>
        </div>
      )}

      <div className="panel">
        <MyDiscordPings leagueId={leagueId} />
      </div>

      <p className="form-actions">
        <button type="button" className="primary" onClick={() => navigate(`/leagues/${leagueId}`)}>Go to {league.data.name}</button>
      </p>
    </section>
  );
}
