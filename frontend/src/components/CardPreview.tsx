import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { apiGet, type CardDetail } from "../api/client";
import CardImage from "./CardImage";
import { cardStats } from "./cardStats";

// Hover (or tap, on touch screens) to see a card's full image and text in a popover. The details
// come from the catalog endpoint, fetched on first preview and then kept for the page's lifetime,
// so a list of cards costs nothing until someone actually looks at one.

const details = new Map<number, Promise<CardDetail>>();
function load(cardId: number): Promise<CardDetail> {
  let p = details.get(cardId);
  if (!p) {
    p = apiGet<CardDetail>(`/explore/cards/${cardId}`);
    p.catch(() => details.delete(cardId));   // let a later hover retry
    details.set(cardId, p);
  }
  return p;
}

const HOVER_DELAY_MS = 250;
const WIDTH = 300;

export default function CardPreview({ cardId, children }: { cardId: number; children: ReactNode }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const timer = useRef<number | undefined>(undefined);
  const [open, setOpen] = useState(false);

  const show = () => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setOpen(true), HOVER_DELAY_MS);
  };
  const hide = () => {
    window.clearTimeout(timer.current);
    setOpen(false);
  };
  useEffect(() => () => window.clearTimeout(timer.current), []);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    const onScroll = () => setOpen(false);
    window.addEventListener("keydown", onKey);
    window.addEventListener("scroll", onScroll, true);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", onScroll, true);
    };
  }, [open]);

  return (
    <span
      ref={anchor}
      className="card-preview-anchor"
      onMouseEnter={show}
      onMouseLeave={hide}
      // Touch screens have no hover: a tap on the anchor toggles the preview instead.
      onPointerUp={(e) => { if (e.pointerType === "touch") setOpen((o) => !o); }}
    >
      {children}
      {open && anchor.current && createPortal(<Popover cardId={cardId} anchor={anchor.current} onClose={hide} />, document.body)}
    </span>
  );
}

function Popover({ cardId, anchor, onClose }: { cardId: number; anchor: HTMLElement; onClose: () => void }) {
  const [card, setCard] = useState<CardDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);

  useEffect(() => {
    let live = true;
    load(cardId).then((c) => live && setCard(c), () => live && setFailed(true));
    return () => { live = false; };
  }, [cardId]);

  // Beside the anchor if there's room (right, else left), clamped inside the viewport with a 16px margin.
  useLayoutEffect(() => {
    const r = anchor.getBoundingClientRect();
    const width = Math.min(WIDTH, window.innerWidth - 32);
    const height = box.current?.offsetHeight ?? 400;
    let left = r.right + 12;
    if (left + width > window.innerWidth - 16) left = r.left - width - 12;
    left = Math.max(16, Math.min(left, window.innerWidth - width - 16));
    const top = Math.max(16, Math.min(r.top - 40, window.innerHeight - height - 16));
    setPos({ left, top });
  }, [anchor, card]);

  return (
    <div
      ref={box}
      className="card-popover"
      role="dialog"
      aria-label={card?.name ?? "Card preview"}
      style={{ left: pos?.left ?? -9999, top: pos?.top ?? 0, width: Math.min(WIDTH, window.innerWidth - 32) }}
      onPointerUp={(e) => { if (e.pointerType === "touch") onClose(); }}
    >
      {failed ? (
        <p className="muted">Couldn't load this card.</p>
      ) : !card ? (
        <p className="muted">Loading…</p>
      ) : (
        <>
          <CardImage passcode={card.passcodes[0]} size="full" alt={card.name} />
          <p className="popover-name">{card.name}</p>
          <p className="muted popover-stats">{card.cardType} · {cardStats(card)}{card.archetype ? ` · ${card.archetype}` : ""}</p>
          <p className="popover-text">{card.description}</p>
        </>
      )}
    </div>
  );
}
