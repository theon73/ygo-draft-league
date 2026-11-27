import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiPost, type InvitePreview } from "../api/client";
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
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);

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
      navigate(`/leagues/${leagueId}`);
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
