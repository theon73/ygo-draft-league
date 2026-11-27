import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

// A small ⓘ button that explains the thing next to it (YGO-29), so the "why" and "how it works"
// can leave the page and only what you need to act stays on screen.
//
// It opens on hover or keyboard focus, and on a tap (phones have no hover); Escape or a tap
// elsewhere closes it. The text is portalled to <body> with fixed positioning, like the card
// preview, so a table cell or a scrolling box can't clip it, and it's clamped inside the viewport.
// The button is described by the tip (aria-describedby), so screen readers read it on focus.

const WIDTH = 288; // px, at most; narrower on a phone

export default function InfoTip({ children, label = "More info" }: { children: ReactNode; label?: string }) {
  const id = useId();
  const button = useRef<HTMLButtonElement>(null);
  const [hover, setHover] = useState(false);
  const [pinned, setPinned] = useState(false); // opened by a tap/click; stays until dismissed
  const open = hover || pinned;

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { setPinned(false); setHover(false); } };
    const onDown = (e: PointerEvent) => {
      if (!button.current?.contains(e.target as Node)) { setPinned(false); setHover(false); }
    };
    const onScroll = () => { setPinned(false); setHover(false); };
    window.addEventListener("keydown", onKey);
    window.addEventListener("pointerdown", onDown);
    window.addEventListener("scroll", onScroll, true);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("pointerdown", onDown);
      window.removeEventListener("scroll", onScroll, true);
    };
  }, [open]);

  return (
    <>
      <button
        ref={button}
        type="button"
        className="info-tip"
        aria-label={label}
        aria-describedby={open ? id : undefined}
        aria-expanded={open}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        onFocus={() => setHover(true)}
        onBlur={() => { setHover(false); setPinned(false); }}
        // A click inside a <label> would also toggle its checkbox; this button only opens the tip.
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setPinned(!pinned);
          if (pinned) setHover(false); // a second click closes it, even while hovered or focused
        }}
      >
        <span aria-hidden="true">i</span>
      </button>
      {open && button.current && createPortal(<Tip id={id} anchor={button.current}>{children}</Tip>, document.body)}
    </>
  );
}

function Tip({ id, anchor, children }: { id: string; anchor: HTMLElement; children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number; below: boolean } | null>(null);
  const width = Math.min(WIDTH, window.innerWidth - 32);

  // Below the button if there's room, else above; centred on it, clamped 16px inside the viewport.
  useLayoutEffect(() => {
    const r = anchor.getBoundingClientRect();
    const height = box.current?.offsetHeight ?? 80;
    const below = r.bottom + 8 + height <= window.innerHeight - 16 || r.top - 8 - height < 16;
    const left = Math.max(16, Math.min(r.left + r.width / 2 - width / 2, window.innerWidth - width - 16));
    setPos({ left, top: below ? r.bottom + 8 : r.top - 8 - height, below });
  }, [anchor, width]);

  return (
    <div ref={box} id={id} role="tooltip" className="info-tip-box"
         style={{ left: pos?.left ?? -9999, top: pos?.top ?? 0, width }}>
      {children}
    </div>
  );
}
