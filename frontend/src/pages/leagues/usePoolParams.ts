import { useSearchParams } from "react-router-dom";
import { MONSTER_ONLY, type MultiParam } from "./poolQuery";

// The pool list's filters live in the URL (see poolQuery.ts). Shared by the Pool tab and the draft
// room's card picker so both filter the same way.
//
// Unlike React's setState, React Router's functional form (prev => next) doesn't chain: `prev` is
// always the URL of the current render, so a second call in the same event overwrites the first.
// Anything that changes several parameters at once must do it in one call (setParams below).
export function usePoolParams() {
  const [params, setSearchParams] = useSearchParams();

  /** Sets several single-value parameters in one URL update; an empty value removes the parameter. */
  const setParams = (values: Record<string, string>) => {
    const names = Object.keys(values);
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      for (const name of names) {
        if (values[name]) next.set(name, values[name]); else next.delete(name);
      }
      if (!names.includes("page")) next.delete("page"); // a new filter starts from the first page
      return next;
    }, { replace: !names.includes("page") && !names.includes("show") });
  };

  const setParam = (name: string, value: string) => setParams({ [name]: value });

  const setList = (name: MultiParam, values: string[]) => setSearchParams((prev) => {
    const next = new URLSearchParams(prev);
    next.delete(name);
    for (const v of values) next.append(name, v);
    next.delete("page");
    if (name === "kind" && values.length > 0) {
      // Filters for kinds no longer shown would only empty the list, so drop them.
      if (!values.includes("MONSTER")) for (const p of MONSTER_ONLY) next.delete(p);
      if (!values.includes("SPELL")) next.delete("spellType");
      if (!values.includes("TRAP")) next.delete("trapType");
    }
    return next;
  }, { replace: true });

  // Clears the browse filters; the curation tab (show) and "available only" stay.
  const clearFilters = () => {
    setSearchParams((prev) => {
      const next = new URLSearchParams();
      for (const keep of ["show", "available"]) if (prev.get(keep)) next.set(keep, prev.get(keep)!);
      return next;
    });
  };

  return { params, setParam, setParams, setList, clearFilters };
}
