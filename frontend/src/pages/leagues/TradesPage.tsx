import CountBadge from "../../components/CountBadge";
import PlayerAvatar from "../../components/PlayerAvatar";
import InfoTip from "../../components/InfoTip";
import { useEffect, useState, type Dispatch, type ReactNode, type SetStateAction } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  apiPost, type AllowanceKind, type Roster, type RosterCard, type TradeAsset, type TradeOffer, type TradesOverview, type TradeThread,
} from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { cardStats } from "../../components/cardStats";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { GROUPS, seasonCardPath, useRosters } from "./RostersPage";
import { useSeason } from "./SeasonLayout";
import { useConfirm, Loading } from "../../components/Feedback";

// The Trades tab (YGO-24): one private conversation per pair of players, with offers,
// counteroffers and messages. Who you're talking to is in the URL (?with=…), and, for a
// commissioner trading for a placeholder seat, who you're acting as (?as=…).

const STATUS: Record<TradeOffer["status"], string> = {
  OPEN: "Open",
  PENDING_APPROVAL: "Accepted, waiting for a commissioner",
  EXECUTED: "Done",
  WITHDRAWN: "Withdrawn",
  CANCELLED: "Cancelled",
  REJECTED: "Rejected by a commissioner",
};

const allowanceLabel = (kind: AllowanceKind, n: number) => `${n} ${kind === "TRADE" ? "trade" : "free-agent claim"}${n === 1 ? "" : "s"}`;
const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
// "unlimited trades" / "1 trade" / "3 claims"
const left = (n: number | null, word: string) => (n === null ? `unlimited ${word}s` : plural(n, word));

export default function TradesPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/trades`;
  const [params, setParams] = useSearchParams();
  const as = params.get("as") ?? "";
  const withId = params.get("with") ?? "";
  const [version, setVersion] = useState(0);
  const overview = useApi<TradesOverview>(`${base}${as ? `?as=${encodeURIComponent(as)}` : ""}`, String(version));
  const refresh = () => setVersion((v) => v + 1);
  // The season header refetches its unread total every minute; when it changes, refetch the
  // conversation list too so each conversation's own count keeps up.
  const unread = season.unreadMessages ?? 0;
  useEffect(() => { refresh(); }, [unread]);

  const choose = (values: Record<string, string>) => setParams((prev) => {
    const next = new URLSearchParams(prev);
    for (const [k, v] of Object.entries(values)) { if (v) next.set(k, v); else next.delete(k); }
    return next;
  });

  if (overview.error) return <p className="bad">Couldn't load trades ({overview.error})</p>;
  if (!overview.data) return <Loading />;
  const o = overview.data;
  const r = o.rules;

  return (
    <>

      {!o.open && <p className="bad">{o.closedReason}.</p>}

      {o.approvals.length > 0 && <Approvals base={base} offers={o.approvals} onDone={refresh} />}

      {o.actors.length === 0 ? (
        <p className="muted">You aren't playing this season, so you have no trades of your own.</p>
      ) : (
        <>
          <div className="pool-toolbar">
            {o.actors.length > 1 && (
              <label className="inline-label">Trading as{" "}
                <select value={o.as ?? ""} onChange={(e) => choose({ as: e.target.value, with: "" })}>
                  {o.actors.map((a) => <option key={a.memberId} value={a.memberId}>{a.name}{a.you ? " (you)" : " (placeholder)"}</option>)}
                </select>
              </label>
            )}
            {/* What's left this week, with the league's trade rules in the tip (YGO-34). */}
            <span className="limits">
              {o.mine && (
                <>
                  <span className="pill accent">{left(o.mine.tradesLeft, "trade")} left</span>
                  <span className="pill">{left(o.mine.claimsLeft, "claim")} left</span>
                  <span className="muted small">{o.week === 0 ? "before week 1 (counts as week 1)" : `week ${o.week}`}</span>
                </>
              )}
              <InfoTip>
                {r.tradesPerWeek === null ? "Unlimited trades" : `${r.tradesPerWeek} trade${r.tradesPerWeek === 1 ? "" : "s"} a week`}, each counting for
                both players{r.tradeDeadlineWeek !== null && `; the deadline is the end of week ${r.tradeDeadlineWeek}`}
                {r.commissionerApproval && "; accepted trades need a commissioner's approval"}. Trades are even: both sides give the same
                number of cards. Conversations are private to the two players.
              </InfoTip>
            </span>
          </div>
          <div className="trades-layout">
            <aside>
              <ul className="thread-list">
                {o.threads.map((t) => (
                  <li key={t.withMemberId} className={t.withMemberId === withId ? "selected" : undefined}>
                    <button onClick={() => choose({ with: t.withMemberId })}>
                      <PlayerAvatar name={t.with} passcode={season.participants.find((p) => p.memberId === t.withMemberId)?.avatar} size="sm" />
                      <span className="grow">{t.with}</span>
                      {t.awaitingYou > 0 && <span className="badge ban-limited">{t.awaitingYou} for you</span>}
                      <CountBadge count={t.unread} />
                      {t.live > t.awaitingYou && <span className="badge">{t.live - t.awaitingYou} open</span>}
                    </button>
                  </li>
                ))}
              </ul>
              <select aria-label="Start a conversation" value="" onChange={(e) => e.target.value && choose({ with: e.target.value })}>
                <option value="">Trade with…</option>
                {o.players.map((p) => <option key={p.memberId} value={p.memberId}>{p.name}</option>)}
              </select>
            </aside>
            <section>
              {withId ? (
                <Thread key={`${o.as}-${withId}`} base={base} as={o.as ?? ""} withId={withId} open={o.open} rules={o.rules} onChange={refresh} />
              ) : (
                <p className="muted">Choose a conversation, or start one with “Trade with…”.</p>
              )}
            </section>
          </div>
        </>
      )}
    </>
  );
}

// One side of an offer: its cards as small thumbnails with names, then any allowances.
function Side({ title, assets }: { title: string; assets: TradeAsset[] }) {
  const { league } = useLeague();
  const { season } = useSeason();
  return (
    <div className="offer-side">
      <h6>{title}</h6>
      {assets.length === 0 && <p className="muted">Nothing</p>}
      <ul>
        {assets.map((a, i) => (
          <li key={i}>
            {a.card ? (
              <CardPreview cardId={a.card.cardId}>
                <CardImage passcode={a.card.passcode} size="small" alt="" className="thumb-xs" />
                <Link to={seasonCardPath(league.id, season.id, a.card.cardId)}>{a.card.name}</Link>
              </CardPreview>
            ) : (
              <span className="allowance">+ {allowanceLabel(a.kind as AllowanceKind, a.quantity)}</span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function OfferBox({ offer, children }: { offer: TradeOffer; children?: ReactNode }) {
  return (
    <div className={`panel offer offer-${offer.status.toLowerCase()}`}>
      <div className="offer-head">
        <span><strong>{offer.from}</strong> → <strong>{offer.to}</strong>{offer.counterOf && <span className="muted"> · counteroffer</span>}</span>
        <span className={`badge status-${offer.status.toLowerCase()}`}>{STATUS[offer.status]}</span>
        <span className="muted offer-time">{new Date(offer.at).toLocaleString()}</span>
      </div>
      <div className="offer-sides">
        <Side title={`${offer.from} gives`} assets={offer.fromGives} />
        <span className="offer-swap" aria-hidden>⇄</span>
        <Side title={`${offer.to} gives`} assets={offer.toGives} />
      </div>
      {offer.cancelReason && <p className="muted">Cancelled: {offer.cancelReason}.</p>}
      {children && <div className="offer-actions">{children}</div>}
    </div>
  );
}

function Approvals({ base, offers, onDone }: { base: string; offers: TradeOffer[]; onDone: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const decide = async (o: TradeOffer, action: "approve" | "reject") => {
    setError(null);
    try {
      await apiPost(`${base}/offers/${o.id}/${action}`);
      onDone();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <>
      <h5>Waiting for your approval</h5>
      {offers.map((o) => (
        <OfferBox key={o.id} offer={o}>
          <p>
            <button className="primary sm" onClick={() => void decide(o, "approve")}>Approve</button>{" "}
            <button className="danger sm" onClick={() => void decide(o, "reject")}>Reject</button>
          </p>
        </OfferBox>
      ))}
      {error && <p className="bad">{error}</p>}
    </>
  );
}

function Thread({ base, as, withId, open, rules, onChange }: {
  base: string; as: string; withId: string; open: boolean; rules: TradesOverview["rules"]; onChange: () => void;
}) {
  const [version, setVersion] = useState(0);
  const thread = useApi<TradeThread>(`${base}/threads/${withId}${as ? `?as=${encodeURIComponent(as)}` : ""}`, String(version));
  const rosters = useRosters();
  const [composing, setComposing] = useState<{ counterOf: string | null; give: number[]; get: number[] } | null>(null);
  const [message, setMessage] = useState("");
  const [error, setError] = useState<string | null>(null);
  const confirm = useConfirm();
  const reload = () => { setVersion((v) => v + 1); onChange(); };
  // Opening the conversation marks it read on the server; once it has loaded, refresh what shows
  // the unread counts (the season header and tab, and this page's conversation list).
  const { reload: reloadSeason, reloadLeague } = useSeason();
  const loaded = !!thread.data;
  useEffect(() => {
    if (loaded) { reloadSeason(); reloadLeague(); onChange(); }
  }, [loaded, withId]); // eslint-disable-line react-hooks/exhaustive-deps

  if (thread.error) return <p className="bad">Couldn't load the conversation ({thread.error})</p>;
  if (!thread.data) return <Loading />;
  const t = thread.data;
  const myRoster = rosters.data?.players.find((p) => p.memberId === as) ?? rosters.data?.players.find((p) => p.you) ?? null;
  const theirRoster = rosters.data?.players.find((p) => p.memberId === withId) ?? null;

  const act = async (o: TradeOffer, action: "accept" | "decline" | "withdraw") => {
    if (action === "decline" && !(await confirm({ title: "Decline this offer?", message: "It disappears for both of you.", confirmLabel: "Decline", danger: true }))) return;
    setError(null);
    try {
      await apiPost(`${base}/offers/${o.id}/${action}`, { as: as || null });
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const send = async () => {
    setError(null);
    try {
      await apiPost(`${base}/threads/${withId}/messages`, { as: as || null, body: message });
      setMessage("");
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  // A counter starts from the offer with the sides swapped: what they gave is what you'd get.
  const counter = (o: TradeOffer) => {
    const iAmFrom = o.fromMemberId === (myRoster?.memberId ?? "");
    const mine = iAmFrom ? o.fromGives : o.toGives;
    const theirs = iAmFrom ? o.toGives : o.fromGives;
    const ids = (a: TradeAsset[]) => a.filter((x) => x.card).map((x) => x.card!.cardId);
    setComposing({ counterOf: o.id, give: ids(mine), get: ids(theirs) });
  };

  return (
    <>
      <h5 className="thread-head">
        With {t.with}
        <span className="muted">
          You: {left(t.mine.tradesLeft, "trade")}, {left(t.mine.claimsLeft, "claim")} left · {t.with}: {left(t.theirs.tradesLeft, "trade")},{" "}
          {left(t.theirs.claimsLeft, "claim")}
        </span>
      </h5>
      {t.items.length === 0 && <p className="muted">No offers or messages yet.</p>}
      <div className="thread">
        {t.items.map((item, i) => item.offer ? (
          <OfferBox key={item.offer.id} offer={item.offer}>
            {(item.offer.canAccept || item.offer.canWithdraw || (open && item.offer.status === "OPEN")) && (
              <p>
                {item.offer.canAccept && <button className="primary" onClick={() => void act(item.offer!, "accept")}>Accept</button>}{" "}
                {item.offer.canDecline && <button className="danger" onClick={() => void act(item.offer!, "decline")}>Decline</button>}{" "}
                {item.offer.canWithdraw && <button className="ghost" onClick={() => void act(item.offer!, "withdraw")}>Withdraw</button>}{" "}
                {open && item.offer.status === "OPEN" && <button onClick={() => counter(item.offer!)}>Counter</button>}
              </p>
            )}
          </OfferBox>
        ) : item.message && <Message key={`m${i}`} items={t.items} i={i} theirId={withId} />)}
      </div>
      {error && <p className="bad">{error}</p>}

      {!composing && (
        <form className="thread-composer" onSubmit={(e) => { e.preventDefault(); if (message.trim()) void send(); }}>
          <input value={message} onChange={(e) => setMessage(e.target.value)} placeholder={`Message ${t.with}`} maxLength={1000} aria-label="Message" />
          <button type="submit" disabled={!message.trim()}>Send</button>
          {open && <button type="button" onClick={() => setComposing({ counterOf: null, give: [], get: [] })}>New offer</button>}
        </form>
      )}

      {composing && (
        <OfferBuilder key={composing.counterOf ?? "new"} base={base} as={as} withId={withId} withName={t.with} initial={composing}
                      mine={myRoster} theirs={theirRoster} rules={rules} onCancel={() => setComposing(null)}
                      onSent={() => { setComposing(null); reload(); }} />
      )}
    </>
  );
}

// Builds an offer: pick cards from each roster (rows you click to select, filterable, by deck
// group), add allowances with steppers, and check the summary (even trades only) before sending.
function OfferBuilder({ base, as, withId, withName, initial, mine, theirs, rules, onCancel, onSent }: {
  base: string; as: string; withId: string; withName: string;
  initial: { counterOf: string | null; give: number[]; get: number[] };
  mine: Roster | null; theirs: Roster | null; rules: TradesOverview["rules"];
  onCancel: () => void; onSent: () => void;
}) {
  const [give, setGive] = useState<number[]>(initial.give);
  const [get, setGet] = useState<number[]>(initial.get);
  const [allow, setAllow] = useState<Record<string, number>>({});
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Allowances are only tradeable when that kind has a limit.
  const kinds: AllowanceKind[] = [
    ...(rules.freeAgencyPerWeek !== null ? ["FREE_AGENCY" as const] : []),
    ...(rules.tradesPerWeek !== null ? ["TRADE" as const] : []),
  ];
  const count = (side: "give" | "get", k: AllowanceKind) => allow[`${side}.${k}`] ?? 0;
  const assets = (cards: number[], side: "give" | "get") => [
    ...cards.map((cardId) => ({ kind: "CARD", cardId })),
    ...kinds.filter((k) => count(side, k) > 0).map((k) => ({ kind: k, quantity: count(side, k) })),
  ];
  const even = give.length === get.length;
  const empty = assets(give, "give").length + assets(get, "get").length === 0;

  const names = (roster: Roster | null, ids: number[]) =>
    ids.map((id) => roster?.cards.find((c) => c.card.cardId === id)?.card.name ?? "?");
  const summary = (roster: Roster | null, ids: number[], side: "give" | "get") => {
    const parts = [...names(roster, ids), ...kinds.filter((k) => count(side, k) > 0).map((k) => allowanceLabel(k, count(side, k)))];
    return parts.length === 0 ? <span className="muted">nothing yet</span> : parts.join(", ");
  };

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await apiPost(`${base}/offers`, {
        as: as || null, toMemberId: withId, give: assets(give, "give"), get: assets(get, "get"), counterOf: initial.counterOf, message: note || null,
      });
      onSent();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  // Both allowance rows are always shown; a kind with no weekly limit can't be traded (there'd be
  // nothing to give), so its row is disabled with the reason.
  const ALL_KINDS: AllowanceKind[] = ["FREE_AGENCY", "TRADE"];
  const stepper = (side: "give" | "get", k: AllowanceKind) => {
    const off = !kinds.includes(k);
    const label = k === "TRADE" ? "Trades" : "Free-agent claims";
    return (
      <div key={k} className={`stepper${off ? " off" : ""}`} title={off ? `${label} are unlimited this season, so they can't be traded` : undefined}>
        <span>{label}{off && <span className="muted"> · unlimited</span>}</span>
        <button type="button" aria-label={`One fewer: ${label}`} disabled={off || count(side, k) === 0}
                onClick={() => setAllow((a) => ({ ...a, [`${side}.${k}`]: Math.max(0, count(side, k) - 1) }))}>−</button>
        <span className="stepper-value">{off ? "–" : count(side, k)}</span>
        <button type="button" aria-label={`One more: ${label}`} disabled={off || count(side, k) >= 10}
                onClick={() => setAllow((a) => ({ ...a, [`${side}.${k}`]: count(side, k) + 1 }))}>+</button>
      </div>
    );
  };

  return (
    <div className="panel offer-builder">
      <p className="builder-title"><strong>{initial.counterOf ? "Counteroffer" : "New offer"} to {withName}</strong></p>
      <div className="builder-sides">
        <CardPicker title="You give" roster={mine} chosen={give} onChange={setGive}>
          {ALL_KINDS.map((k) => stepper("give", k))}
        </CardPicker>
        <CardPicker title={`${withName} gives`} roster={theirs} chosen={get} onChange={setGet}>
          {ALL_KINDS.map((k) => stepper("get", k))}
        </CardPicker>
      </div>

      <div className="builder-summary">
        <p><strong>You give:</strong> {summary(mine, give, "give")}</p>
        <p><strong>You get:</strong> {summary(theirs, get, "get")}</p>
        <p className={even ? "muted" : "bad"}>
          {even ? (give.length === 0 ? "No cards picked yet (an offer can also be allowances only)." : `${plural(give.length, "card")} each way.`) : `Trades are even: pick the same number of cards on each side (now ${give.length} for ${get.length}).`}
        </p>
        {kinds.length > 0 && <p className="muted">Allowances are this week's; the trade itself still uses one trade for each of you.</p>}
        <div className="builder-send">
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Add a message (optional)" maxLength={1000} aria-label="Message with the offer" />
          <button className="primary" onClick={() => void submit()} disabled={busy || !even || empty}>Send offer</button>
          <button className="ghost" onClick={onCancel} disabled={busy}>Cancel</button>
        </div>
        {error && <p className="bad">{error}</p>}
      </div>
    </div>
  );
}

// One roster as clickable rows (thumbnail, name, stats), grouped by deck, with a filter box.
function CardPicker({ title, roster, chosen, onChange, children }: {
  title: string; roster: Roster | null; chosen: number[]; onChange: Dispatch<SetStateAction<number[]>>; children?: ReactNode;
}) {
  const [filter, setFilter] = useState("");
  const match = (c: RosterCard) => c.card.name.toLowerCase().includes(filter.trim().toLowerCase());
  // The updater form, so quick clicks each build on the latest selection rather than a stale one.
  const toggle = (id: number) => onChange((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  return (
    <div className="card-picker">
      <div className="picker-head">
        <h6>{title} <span className="muted">({chosen.length} selected)</span></h6>
        <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter by name" aria-label={`Filter: ${title}`} />
      </div>
      <div className="picker-list">
        {!roster && <Loading />}
        {GROUPS.map(({ group, label }) => {
          const cards = roster?.cards.filter((c) => c.group === group && (match(c) || chosen.includes(c.card.cardId))) ?? [];
          if (cards.length === 0) return null;
          return (
            <div key={group}>
              <div className="picker-group">{label}</div>
              {cards.map((c) => {
                const on = chosen.includes(c.card.cardId);
                return (
                  <button key={c.card.cardId} type="button" className={`pick-row${on ? " on" : ""}`} aria-pressed={on} onClick={() => toggle(c.card.cardId)}>
                    <span className="pick-mark" aria-hidden>{on ? "✓" : ""}</span>
                    <CardImage passcode={c.card.passcode} size="small" alt="" className="thumb-xs" />
                    <span className="pick-text">
                      <span className="pick-name">{c.card.name}</span>
                      <span className="pick-stats">{cardStats(c.card)}</span>
                    </span>
                  </button>
                );
              })}
            </div>
          );
        })}
      </div>
      {children && <div className="picker-allowances">{children}</div>}
    </div>
  );
}

// A chat message (YGO-34): yours on the right, theirs on the left with their avatar. Consecutive
// messages from one person within a few minutes form a group: the name shows on the first, the
// time on the last, and a date line marks a new day.
const GROUP_GAP_MS = 5 * 60_000;

function Message({ items, i, theirId }: { items: TradeThread["items"]; i: number; theirId: string }) {
  const { season } = useSeason();
  const m = items[i].message!;
  const prev = items[i - 1]?.message ?? undefined;
  const next = items[i + 1]?.message ?? undefined;
  const at = Date.parse(m.at);
  const sameGroup = (a: typeof m | undefined, b: typeof m | undefined) =>
    !!a && !!b && a.authorMemberId === b.authorMemberId && Math.abs(Date.parse(a.at) - Date.parse(b.at)) < GROUP_GAP_MS;
  const first = !sameGroup(prev, m);
  const last = !sameGroup(m, next);
  const prevAt = items[i - 1] ? Date.parse(items[i - 1].at) : null;
  const newDay = prevAt === null || new Date(prevAt).toDateString() !== new Date(at).toDateString();
  const theirs = m.authorMemberId === theirId;
  const avatar = season.participants.find((p) => p.memberId === m.authorMemberId)?.avatar;
  return (
    <>
      {newDay && <div className="msg-day">{new Date(at).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" })}</div>}
      <div className={`msg-row ${theirs ? "theirs" : "mine"}${first ? " first" : ""}${last ? " last" : ""}`}>
        {theirs && (first ? <PlayerAvatar name={m.author} passcode={avatar} size="sm" /> : <span className="msg-gutter" />)}
        <div className="msg-col">
          {theirs && first && <span className="msg-author">{m.author}</span>}
          <div className="bubble">{m.body}</div>
          {last && (
            <span className="msg-time">
              {new Date(at).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}
              {m.typedBy && ` · typed by ${m.typedBy}`}
            </span>
          )}
        </div>
      </div>
    </>
  );
}
