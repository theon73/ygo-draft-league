// A small count bubble for unread things (YGO-34): nothing at zero, "9+" past nine.
export default function CountBadge({ count, label }: { count: number | undefined; label?: string }) {
  if (!count) return null;
  return (
    <span className="count-badge" aria-label={label ?? `${count} unread`} title={label ?? `${count} unread`}>
      {count > 9 ? "9+" : count}
    </span>
  );
}
