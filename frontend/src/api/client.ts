// Thin fetch wrapper. Swap in TanStack Query hooks once there's more than a couple of endpoints.

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { credentials: "include" });
  if (!res.ok) throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  return res.json() as Promise<T>;
}

// ---- Types mirroring backend DTOs ----

export interface Health {
  status: string;
  time: string;
}

export interface Player {
  id: number;
  displayName: string;
}
