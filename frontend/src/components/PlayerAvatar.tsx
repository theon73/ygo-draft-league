import { useEffect, useState } from "react";
import { cardImageUrl } from "./CardImage";

// A player's avatar (YGO-30): a card's artwork, cropped round, or their initials when they haven't
// chosen one (or the art can't load). Sizes: xs (inline, e.g. the bracket), sm (match tiles,
// standings), md (roster columns, the board), lg (a roster's header, the picker preview).

export type AvatarSize = "xs" | "sm" | "md" | "lg";

/** "Theo Novak" → "TN", "bob" → "B". */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "?") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

export default function PlayerAvatar({ name, passcode, size = "sm" }: { name: string; passcode?: number | null; size?: AvatarSize }) {
  // The image cache answers 503 while it fetches an artwork for the first time, so a failed load
  // shows initials for a few seconds and tries again (twice). The state belongs to one passcode,
  // so choosing another starts fresh.
  const [tries, setTries] = useState<{ passcode: number; failed: number; waiting: boolean } | null>(null);
  const t = passcode && tries?.passcode === passcode ? tries : { passcode: passcode ?? 0, failed: 0, waiting: false };
  useEffect(() => {
    if (!t.waiting || t.failed > 2) return;
    const timer = window.setTimeout(() => setTries({ ...t, waiting: false }), 4000);
    return () => window.clearTimeout(timer);
  }, [t.passcode, t.failed, t.waiting]); // eslint-disable-line react-hooks/exhaustive-deps
  if (passcode && !t.waiting && t.failed <= 2) {
    const url = cardImageUrl(passcode, "art");
    return (
      <img key={`${passcode}-${t.failed}`} className={`player-avatar ${size}`} src={t.failed ? `${url}?retry=${t.failed}` : url} alt=""
           loading="lazy" decoding="async" onError={() => setTries({ passcode, failed: t.failed + 1, waiting: true })} />
    );
  }
  return <span className={`player-avatar initials ${size}`} aria-hidden="true">{initials(name)}</span>;
}
