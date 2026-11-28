import { useState } from "react";
import { inviteLink, type InviteView } from "../../api/client";

/** Copies to the clipboard, or (where the browser refuses) shows the text to copy by hand. */
export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    window.prompt("Copy this link:", text);
  }
}

// The league's active invites, compact enough for the Members page's side panel (YGO-38).
export default function InviteList({ invites, onRevoke }: { invites: InviteView[]; onRevoke: (code: string) => void }) {
  const [copied, setCopied] = useState<string | null>(null);

  if (invites.length === 0) return <p className="muted small">No active links.</p>;

  const copy = async (code: string) => {
    await copyText(inviteLink(code));
    setCopied(code);
  };
  const expires = (iso: string | null) =>
    iso ? `expires ${new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" })}` : "never expires";

  return (
    <ul className="invite-list">
      {invites.map((i) => (
        <li key={i.code}>
          <div>
            <div>{i.kind === "CLAIM" ? <>Claim <strong>{i.seatName}</strong>'s seat</> : "Join the league"}</div>
            <div className="muted small">
              {i.maxUses === null ? `${i.useCount} used` : `${i.useCount} of ${i.maxUses} used`} · {expires(i.expiresAt)}
            </div>
          </div>
          <div className="invite-actions">
            <button className="sm" onClick={() => void copy(i.code)}>{copied === i.code ? "Copied" : "Copy"}</button>
            <button className="ghost sm danger-text" onClick={() => onRevoke(i.code)}>Revoke</button>
          </div>
        </li>
      ))}
    </ul>
  );
}
