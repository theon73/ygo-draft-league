import { useEffect, useRef, useState } from "react";
import { apiGet, type CardPage } from "../api/client";
import CardImage from "./CardImage";
import PlayerAvatar from "./PlayerAvatar";

// Choose a card's art as an avatar (YGO-30): search the whole catalog by name, pick a card (and,
// for cards with several artworks, which one), and save. A native <dialog>, like the confirm.
//
// `scopes` offers where it applies when there's a choice: "every season" (the member's default)
// or "this season only" (an override). With one scope, no choice is shown.

export type AvatarScope = "profile" | "default" | "season";
export interface AvatarChoice { cardId: number; passcode: number }

export default function AvatarPicker({ name, current, canRemove = !!current, note, scopes, initialScope, onSave, onClose }: {
  name: string;
  current: number | null | undefined;
  canRemove?: boolean; // there's an avatar of this kind to remove (not one inherited from elsewhere)
  note?: string; // replaces the default line under the preview
  scopes: { scope: AvatarScope; label: string }[];
  initialScope?: AvatarScope;
  onSave: (choice: AvatarChoice | null, scope: AvatarScope) => Promise<void>;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [q, setQ] = useState("");
  const [results, setResults] = useState<CardPage["items"] | null>(null);
  const [card, setCard] = useState<CardPage["items"][number] | null>(null);
  const [passcode, setPasscode] = useState<number | null>(null);
  const [scope, setScope] = useState<AvatarScope>(initialScope ?? scopes[0].scope);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal();
  }, []);

  // Search as you type, a moment after the last keystroke; a stale answer never replaces a newer one.
  useEffect(() => {
    const term = q.trim();
    if (term.length < 2) { setResults(null); return; }
    let live = true;
    const t = window.setTimeout(() => {
      apiGet<CardPage>(`/explore/cards?q=${encodeURIComponent(term)}`)
        .then((page) => live && setResults(page.items))
        .catch((e: Error) => live && setError(e.message));
    }, 250);
    return () => { live = false; window.clearTimeout(t); };
  }, [q]);

  const save = async (choice: AvatarChoice | null) => {
    setBusy(true);
    setError(null);
    try {
      await onSave(choice, scope);
      onClose();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  const shown = passcode ?? current ?? null;
  return (
    <dialog ref={ref} className="confirm avatar-picker" aria-labelledby="avatar-title"
            onCancel={(e) => { e.preventDefault(); onClose(); }}
            onClick={(e) => { if (e.target === ref.current) onClose(); }}>
      <div className="dialog-body">
        <h4 id="avatar-title">Choose an avatar</h4>
        <div className="avatar-preview">
          <PlayerAvatar name={name} passcode={shown} size="lg" />
          <div>
            <strong>{card?.name ?? (current ? "Current avatar" : "No avatar yet")}</strong>
            <p className="muted small">{note ?? "Any card's art. It shows on rosters, the draft board, matches and standings."}</p>
          </div>
        </div>

        {card && (card.passcodes?.length ?? 0) > 1 && (
          <div className="avatar-arts" role="radiogroup" aria-label="Artwork">
            {card.passcodes!.map((p, i) => (
              <button key={p} type="button" role="radio" aria-checked={passcode === p} className={passcode === p ? "selected" : undefined}
                      onClick={() => setPasscode(p)} title={`Artwork ${i + 1}`}>
                <PlayerAvatar name={card.name} passcode={p} size="md" />
              </button>
            ))}
          </div>
        )}

        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search cards by name" autoFocus aria-label="Search cards" />
        <div className="avatar-results">
          {results === null ? (
            <p className="muted small">Type at least two letters.</p>
          ) : results.length === 0 ? (
            <p className="muted small">No cards match.</p>
          ) : (
            <ul>
              {results.filter((c) => c.passcodes?.length).map((c) => (
                <li key={c.id}>
                  <button type="button" className={card?.id === c.id ? "selected" : undefined}
                          onClick={() => { setCard(c); setPasscode(c.passcodes![0]); }} title={c.name}>
                    <CardImage passcode={c.passcodes![0]} size="small" alt="" />
                    <span>{c.name}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {scopes.length > 1 && (
          <fieldset className="avatar-scope">
            {scopes.map((s) => (
              <label key={s.scope} className="check">
                <input type="radio" name="avatar-scope" checked={scope === s.scope} onChange={() => setScope(s.scope)} /> {s.label}
              </label>
            ))}
          </fieldset>
        )}
        {error && <p className="bad">{error}</p>}
        <div className="dialog-actions">
          {canRemove && <button type="button" className="ghost" disabled={busy} onClick={() => void save(null)} style={{ marginRight: "auto" }}>Remove</button>}
          <button type="button" className="ghost" onClick={onClose}>Cancel</button>
          <button type="button" className="primary" disabled={busy || !card || !passcode}
                  onClick={() => card && passcode && void save({ cardId: card.id, passcode })}>
            Save avatar
          </button>
        </div>
      </div>
    </dialog>
  );
}
