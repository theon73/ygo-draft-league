import { useEffect, useState } from "react";

// Card images are addressed as {size}/{passcode}.jpg under one base URL. In dev the base is the
// backend's cache endpoint; for a public deployment it can point straight at the R2 bucket
// (VITE_CARD_IMAGE_BASE at build time) with no other code changes. See PLANNING.md "Card images".
const BASE = (import.meta.env.VITE_CARD_IMAGE_BASE ?? "/api/card-images").replace(/\/$/, "");

export type CardImageSize = "small" | "full";

export function cardImageUrl(passcode: number, size: CardImageSize): string {
  return `${BASE}/${size}/${passcode}.jpg`;
}

const MAX_RETRIES = 3;

// A card image with a fixed-ratio placeholder while it loads. The backend answers 503 while an
// image is still being fetched from YGOPRODeck for the first time, so a failed load is retried a
// few times (5 s apart) before showing "unavailable".
export default function CardImage({ passcode, size, alt, className }: {
  passcode: number;
  size: CardImageSize;
  alt: string;
  className?: string;
}) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<"loading" | "loaded" | "failed">("loading");

  useEffect(() => {
    setAttempt(0);
    setState("loading");
  }, [passcode, size]);

  useEffect(() => {
    if (state !== "failed" || attempt >= MAX_RETRIES) return;
    const t = window.setTimeout(() => {
      setAttempt((a) => a + 1);
      setState("loading");
    }, 5000);
    return () => window.clearTimeout(t);
  }, [state, attempt]);

  const giveUp = state === "failed" && attempt >= MAX_RETRIES;
  return (
    <span className={`card-image card-image-${size} ${state} ${className ?? ""}`}>
      {giveUp ? (
        <span className="card-image-missing">Image unavailable</span>
      ) : (
        <img
          // The retry count only changes the URL after a failure; successful loads keep the plain URL so caching works.
          key={attempt}
          src={attempt === 0 ? cardImageUrl(passcode, size) : `${cardImageUrl(passcode, size)}?retry=${attempt}`}
          alt={alt}
          loading="lazy"
          decoding="async"
          onLoad={() => setState("loaded")}
          onError={() => setState("failed")}
        />
      )}
    </span>
  );
}
