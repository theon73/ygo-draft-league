import { useState } from "react";

// A searchable checkbox list for a multi-select filter with many options (monster types,
// archetypes). Collapsed, it shows what's chosen.
export default function MultiSelect({ label, options, selected, onChange }: {
  label: string;
  options: string[];
  selected: string[];
  onChange: (values: string[]) => void;
}) {
  const [find, setFind] = useState("");
  if (options.length === 0) return null;
  const shown = options.filter((o) => o.toLowerCase().includes(find.trim().toLowerCase()));
  const toggle = (v: string) => onChange(selected.includes(v) ? selected.filter((x) => x !== v) : [...selected, v]);
  const summary = selected.length === 0 ? "any" : selected.length <= 2 ? selected.join(", ") : `${selected.length} chosen`;
  return (
    <details className="multi-select">
      <summary><span className="chip-label">{label}</span> {summary}</summary>
      <div className="multi-select-panel">
        {options.length > 8 && (
          <input value={find} onChange={(e) => setFind(e.target.value)} placeholder={`Find ${label.toLowerCase()}…`} aria-label={`Find ${label}`} />
        )}
        <div className="multi-select-options">
          {shown.map((o) => (
            <label key={o} className="check">
              <input type="checkbox" checked={selected.includes(o)} onChange={() => toggle(o)} /> {o}
            </label>
          ))}
          {shown.length === 0 && <span className="muted">No match</span>}
        </div>
        {selected.length > 0 && <button type="button" onClick={() => onChange([])}>Clear</button>}
      </div>
    </details>
  );
}
