import { useEffect, useState } from "react";
import { apiGet, apiPost, type DevUser } from "../api/client";
import { useSession } from "./session";

// Header widget: who's signed in, sign in / sign out, and (dev profile only) "log in as…".
export default function AccountMenu() {
  const { session, refresh } = useSession();
  const [devUsers, setDevUsers] = useState<DevUser[]>([]);
  const [busy, setBusy] = useState(false);

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
          {user.avatarUrl
            ? <img className="avatar" src={user.avatarUrl} alt="" referrerPolicy="no-referrer" />
            : <span className="avatar initials" aria-hidden="true">{initials(user.displayName)}</span>}
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

/** "Theo Novak" → "TN", "bob" → "B". */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "?") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}
