import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { apiGet, type Session } from "../api/client";

// The signed-in user is needed all over the app (header, admin buttons, later league pages), so
// it's loaded once and shared through React context instead of each page fetching it.
// refresh() re-reads it after logging in or out.

interface SessionState {
  session: Session | null; // null while the first load is in flight
  error: string | null;
  refresh: () => Promise<void>;
}

const SessionContext = createContext<SessionState | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setSession(await apiGet<Session>("/auth/session"));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return <SessionContext.Provider value={{ session, error, refresh }}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error("useSession must be used inside <SessionProvider>");
  return ctx;
}
