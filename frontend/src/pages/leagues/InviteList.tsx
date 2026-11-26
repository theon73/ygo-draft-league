import { useState } from "react";
import { inviteLink, type InviteView } from "../../api/client";
import { roleLabel } from "./roles";

export default function InviteList({ invites, onRevoke }: { invites: InviteView[]; onRevoke: (code: string) => void }) {
  const [copied, setCopied] = useState<string | null>(null);

  if (invites.length === 0) return <p className="muted">No active invites.</p>;

  const copy = async (code: string) => {
    try {
      await navigator.clipboard.writeText(inviteLink(code));
      setCopied(code);
    } catch {
      window.prompt("Copy this invite link:", inviteLink(code));
    }
  };

  return (
    <table>
      <thead>
        <tr><th>For</th><th>Uses</th><th>Expires</th><th /></tr>
      </thead>
      <tbody>
        {invites.map((i) => (
          <tr key={i.code}>
            <td>{i.kind === "CLAIM" ? `Claim seat: ${i.seatName}` : roleLabel[i.role!]}</td>
            <td>{i.useCount} / {i.maxUses ?? "∞"}</td>
            <td>{i.expiresAt ? new Date(i.expiresAt).toLocaleDateString() : "Never"}</td>
            <td className="actions">
              <button onClick={() => void copy(i.code)}>{copied === i.code ? "Copied" : "Copy link"}</button>
              <button onClick={() => onRevoke(i.code)}>Revoke</button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
