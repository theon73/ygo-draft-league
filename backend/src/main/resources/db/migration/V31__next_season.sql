-- Next season (YGO-39). A season remembers the season it was copied from, so its seat draw can
-- offer that season's reverse standings. Null for every earlier season and for blank ones.
alter table season add column copied_from_season_id bigint references season (id);

-- How a recorded seat order was made: a random draw (every earlier row) or the reverse of an
-- earlier season's final standings (based_on_season_id says which).
alter table season_seat_draw add column method text not null default 'RANDOM'
    check (method in ('RANDOM', 'REVERSE_STANDINGS'));
alter table season_seat_draw add column based_on_season_id bigint references season (id);
