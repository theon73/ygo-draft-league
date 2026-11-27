// "This is week 3" next to the inputs that ask which week a change takes effect from, so nobody has
// to look up the calendar. Week 0 is the time before week 1; after the last regular-season week the
// number keeps counting (the playoffs have no weeks of their own).
export function currentWeekNote(current: number | null, regularWeeks?: number | null): string {
  if (current === null) return "";
  if (current === 0) return "It's before week 1 now (week 0).";
  if (regularWeeks && current > regularWeeks) return `The regular season's ${regularWeeks} weeks are over (it's week ${current} now).`;
  return `It's week ${current} now${regularWeeks ? ` of ${regularWeeks}` : ""}.`;
}
