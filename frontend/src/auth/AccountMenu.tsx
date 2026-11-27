import { useEffect, useState } from "react";
import { apiGet, apiPost, apiPut, type DevUser } from "../api/client";
import AvatarPicker from "../components/AvatarPicker";
import PlayerAvatar from "../components/PlayerAvatar";
import { useSession } from "./session";

// Header widget: who's signed in, sign in / sign out, and (dev profile only) "log in as…".
export default function AccountMenu() {
  const { session, refresh } = useSession();
  const [devUsers, setDevUsers] = useState<DevUser[]>([]);
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);

  useEffect(() => {
    if (session?.devLogin) apiGet<DevUser[]>("/dev/users").then(setDevUsers).catch(() => setDevUsers([]));
  }, [session?.devLogin]);

  if (!session) return null;

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await action();
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  const user = session.user;
  return (
    <div className="account">
      {user ? (
        <>
          {/* The profile avatar (card art, YGO-30) if chosen, else the sign-in photo, else initials.
              Clicking it opens the picker. */}
          <button className="avatar avatar-button" onClick={() => setPicking(true)} title="Change your avatar" aria-label="Change your avatar">
            {!user.avatar && user.avatarUrl
              ? <img className="player-avatar sm" src={user.avatarUrl} alt="" referrerPolicy="no-referrer" />
              : <PlayerAvatar name={user.displayName} passcode={user.avatar} size="sm" />}
          </button>
          {picking && (
            <AvatarPicker name={user.displayName} current={user.avatar} onClose={() => setPicking(false)}
                          scopes={[{ scope: "profile", label: "Everywhere (your profile)" }]}
                          note="Your profile avatar shows in the header and in every league where you haven't picked a different one."
                          onSave={async (choice) => {
                            await apiPut("/me/avatar", choice ?? { cardId: null });
                            await refresh();
                          }} />
          )}
          <span className="account-name" title={user.identity}>
            {user.displayName}
            {user.admin && <span className="badge neutral">admin</span>}
          </span>
          <button className="ghost sm" disabled={busy} onClick={() => run(() => apiPost("/auth/logout"))}>Sign out</button>
        </>
      ) : session.providers.includes("google") ? (
        // A full-page navigation, not fetch: the OAuth flow redirects through Google and back.
        <a className="button primary sm" href="/api/oauth2/authorization/google">Sign in with Google</a>
      ) : (
        !session.devLogin && <span className="muted">Sign-in isn't configured</span>
      )}

      {session.devLogin && devUsers.length > 0 && (
        <select
          aria-label="Log in as (dev)"
          disabled={busy}
          value=""
          onChange={(e) => {
            const subject = e.target.value;
            if (subject) void run(() => apiPost("/dev/login", { subject }));
          }}
        >
          <option value="">Dev: log in as…</option>
          {devUsers.map((u) => (
            <option key={u.subject} value={u.subject}>
              {u.displayName}
              {u.admin ? " (admin)" : ""}
            </option>
          ))}
        </select>
      )}
    </div>
  );
}
