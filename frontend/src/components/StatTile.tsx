import type { ReactNode } from "react";
import InfoTip from "./InfoTip";

// A small labelled figure for summary rows (YGO-29): a label (with an optional ⓘ), a big value,
// an optional line under it and anything extra (e.g. a progress bar). Lay several out in a
// <div className="stat-tiles">, which wraps them into a responsive grid.
export default function StatTile({ label, value, sub, tip, children }: {
  label: string; value: ReactNode; sub?: ReactNode; tip?: ReactNode; children?: ReactNode;
}) {
  return (
    <div className="stat-tile">
      <div className="stat-label">{label}{tip && <InfoTip label={`About ${label.toLowerCase()}`}>{tip}</InfoTip>}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
      {children}
    </div>
  );
}

/** A thin bar showing `done` of `total`. */
export function ProgressBar({ done, total }: { done: number; total: number }) {
  const pct = total > 0 ? Math.min(100, Math.round((done / total) * 100)) : 0;
  return (
    <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done}>
      <span style={{ width: `${pct}%` }} />
    </div>
  );
}
