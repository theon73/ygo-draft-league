-- Ownership history (YGO-22): every change of who holds a card, one row per change, in order.
--
-- So far the only source is the draft (each pick or auto-pick still in effect). Free agency and
-- trades (YGO-23, YGO-24) will add their executed lines here with "create or replace", in the same
-- shape: from_player_id is who gave the card up (null for the draft or a free-agent claim),
-- to_player_id who got it (null for a drop back to free agency). week is the league week the change
-- happened in; the draft is always week 0 (before week 1). draft_pick is the board slot, for draft rows.
--
-- card_holding (who holds what now) is rebuilt on top of it, so the two can never disagree, and
-- "who held what in week N" is the same sum with a week condition.
create view card_ownership_event as
select d.season_id,
       d.card_id,
       null::bigint      as from_player_id,
       d.season_player_id as to_player_id,
       1                 as quantity,
       d.made_at         as at,
       0                 as week,
       'DRAFT'::text     as source,
       d.overall_pick    as draft_pick,
       d.made_by         as made_by,
       d.sequence        as ordinal
from draft_pick d
where d.kind in ('PICK', 'AUTO')
  and not exists (select 1 from draft_pick u where u.season_id = d.season_id and u.undoes = d.sequence);

-- Same columns as before (season, card, player, quantity), so nothing that reads it changes.
create or replace view card_holding as
select season_id, card_id, season_player_id, sum(delta)::int as quantity
from (select season_id, card_id, to_player_id as season_player_id, quantity as delta
      from card_ownership_event where to_player_id is not null
      union all
      select season_id, card_id, from_player_id, -quantity
      from card_ownership_event where from_player_id is not null) moves
group by season_id, card_id, season_player_id
having sum(delta) > 0;
