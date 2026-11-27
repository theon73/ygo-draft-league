-- Playoffs (YGO-27).
--
-- Play-in and playoff matches live in season_match too, under the season's PLAY_IN and PLAYOFFS
-- stages. They aren't tied to league weeks: a match gets its players as earlier results come in
-- (slot_a / slot_b say from where: "SEED:5", "WINNER:<match id>", "LOSER:<match id>"), and has one
-- week from when both players are known (ready_at -> deadline_at). Regular-season matches keep
-- their week and leave these null.
alter table season_match alter column week drop not null;
alter table season_match alter column round drop not null;
alter table season_match add column slot_a      text;
alter table season_match add column slot_b      text;
alter table season_match add column label       text;          -- "Play-in: 5 v 6", "Semifinal", "Final", …
alter table season_match add column bracket_round int;         -- 1-based within its stage
alter table season_match add column best_of     int check (best_of between 1 and 9);   -- fixed when the bracket is made
alter table season_match add column ready_at    timestamptz;   -- both players known
alter table season_match add column deadline_at timestamptz;   -- a week after that
alter table season_match add column ordinal     int;           -- the bracket's order (sources point at earlier ones)
alter table season_match add constraint season_match_week_or_slot_check check (week is not null or slot_a is not null);

-- The seeds, frozen from the standings when a commissioner starts the playoffs.
create table playoff_seed (
    stage_id         bigint not null references stage (id),
    seed             int    not null check (seed >= 1),
    season_player_id bigint not null references season_player (id),
    primary key (stage_id, seed),
    unique (stage_id, season_player_id)
);

-- The champion: the final's winner, or (without playoffs) the top of the standings, set when the
-- season completes.
alter table season add column champion_player_id bigint references season_player (id);
alter table season add column completed_at timestamptz;

-- When the playoffs started (and seeding froze); trades and free agency close then.
alter table stage add column started_at timestamptz;
alter table stage add column started_by bigint references app_user (id);

-- The play-in and playoffs are single elimination.
alter table stage drop constraint stage_format_check;
alter table stage add constraint stage_format_check check (format in ('ROUND_ROBIN', 'SINGLE_ELIMINATION'));

-- Bracket matches start with no players; the V22 check ("a is distinct from b") fails when both are
-- null, so it only compares them once both are known.
alter table season_match drop constraint season_match_check;
alter table season_match add constraint season_match_players_check
    check (player_a_id is null or player_b_id is null or player_a_id <> player_b_id);
