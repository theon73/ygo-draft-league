import { useEffect, useState } from "react";

// Card images are addressed as {size}/{passcode}.jpg under one base URL. In dev the base is the
// backend's cache endpoint; for a public deployment it can point straight at the R2 bucket
// (VITE_CARD_IMAGE_BASE at build time) with no other code changes. See PLANNING.md "Card images".
const BASE = (import.meta.env.VITE_CARD_IMAGE_BASE ?? "/api/card-images").replace(/\/$/, "");

export type CardImageSize = "small" | "full" | "art"; // art: the artwork alone, cropped (avatars)

export function cardImageUrl(passcode: number, size: CardImageSize): string {
  return `${BASE}/${size}/${passcode}.jpg`;
}

const MAX_RETRIES = 3;

interface Props {
  passcode: number;
  size: CardImageSize;
  alt: string;
  className?: string;
}

// A card image with a fixed-ratio placeholder while it loads. The backend answers 503 while an
// image is still being fetched from YGOPRODeck for the first time, so a failed load is retried a
// few times (5 s apart) before showing "unavailable".
//
// Keyed on passcode + size, so a different image always starts from fresh state. (An earlier
// version reset its state in an effect instead; with images already in the browser cache the
// load event could fire before that effect ran, the reset then hid an already-loaded image, and
// with no second load event it stayed invisible.)
export default function CardImage(props: Props) {
  return <CardImageFor key={`${props.passcode}-${props.size}`} {...props} />;
}

function CardImageFor({ passcode, size, alt, className }: Props) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<"loading" | "loaded" | "failed">("loading");

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
          // Belt and braces: an image the browser already finished (e.g. from its cache) when the
          // element attaches counts as loaded even if we never see its load event.
          ref={(el) => {
            if (el?.complete && el.naturalWidth > 0) setState((s) => (s === "loading" ? "loaded" : s));
          }}
          onLoad={() => setState("loaded")}
          onError={() => setState("failed")}
        />
      )}
    </span>
  );
}
