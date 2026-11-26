// A row of toggle chips for a multi-select filter with a handful of options. Nothing selected
// means "any".
export default function ChipGroup({ label, options, selected, onChange }: {
  label: string;
  options: { value: string; label: string }[];
  selected: string[];
  onChange: (values: string[]) => void;
}) {
  if (options.length === 0) return null;
  const toggle = (v: string) => onChange(selected.includes(v) ? selected.filter((x) => x !== v) : [...selected, v]);
  return (
    <div className="chip-group" role="group" aria-label={label}>
      <span className="chip-label">{label}</span>
      {options.map((o) => (
        <button key={o.value} type="button" className={`chip${selected.includes(o.value) ? " on" : ""}`}
                aria-pressed={selected.includes(o.value)} onClick={() => toggle(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}
