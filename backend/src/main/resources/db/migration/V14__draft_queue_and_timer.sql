-- Pick queues, away mode and timer outcomes (YGO-18).

-- New kinds of pick-log rows:
--   AUTO  a card picked automatically from the player's queue (timer expiry or away mode); no made_by.
--   SKIP  the timer ran out and the draft moved on without a card; no card, no made_by.
-- makeup marks a row that fills an earlier SKIP's slot. Make-ups don't move the clock: the slot on
-- the clock follows from the other rows.
alter table draft_pick alter column card_id drop not null;
alter table draft_pick alter column made_by drop not null;
alter table draft_pick drop constraint draft_pick_kind_check;
alter table draft_pick add constraint draft_pick_kind_check check (kind in ('PICK', 'AUTO', 'SKIP'));
alter table draft_pick add column makeup boolean not null default false;
alter table draft_pick add constraint draft_pick_shape_check check (
    case kind
        when 'PICK' then card_id is not null and made_by is not null
        when 'AUTO' then card_id is not null and made_by is null
        when 'SKIP' then card_id is null and made_by is null and not makeup
    end);
-- A slot is made up at most once.
create unique index draft_pick_one_makeup on draft_pick (season_id, overall_pick) where makeup;

create or replace view card_holding as
select season_id, card_id, season_player_id, count(*)::int as quantity
from draft_pick
where kind in ('PICK', 'AUTO')
group by season_id, card_id, season_player_id;

-- A player's private pick queue: the cards they want, in order. This is a preference, not league
-- state, so it's edited in place rather than logged. Only the player sees it.
create table draft_queue (
    season_player_id bigint      not null references season_player (id) on delete cascade,
    card_id          bigint      not null references card (id),
    position         int         not null,
    added_at         timestamptz not null default now(),
    primary key (season_player_id, card_id)
);
create index draft_queue_order_idx on draft_queue (season_player_id, position);

-- Away mode: pick from my queue as soon as I'm on the clock. Turns itself off when the queue has
-- nothing available.
alter table season_player add column draft_away boolean not null default false;
