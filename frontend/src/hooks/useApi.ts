import { useCallback, useEffect, useState } from "react";
import { ApiError, apiGet } from "../api/client";

// Fetches `path` on mount and whenever it (or `refetchKey`) changes. Pass null to skip fetching
// (e.g. while signed out). `refetchKey` is for things that change the answer without changing
// the URL, like which user is signed in. `status` is the HTTP status of a failed request, so pages can tell a 404 from
// an outage. `reload()` refetches the same path.
export function useApi<T>(path: string | null, refetchKey?: string) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<number | null>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (path === null) return;
    let cancelled = false;
    setError(null);
    setStatus(null);
    apiGet<T>(path)
      .then((d) => {
        if (!cancelled) setData(d);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setData(null);
        setError(e.message);
        setStatus(e instanceof ApiError ? e.status : null);
      });
    return () => {
      cancelled = true;
    };
  }, [path, refetchKey, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  return { data, error, status, loading: path !== null && data === null && error === null, reload };
}
