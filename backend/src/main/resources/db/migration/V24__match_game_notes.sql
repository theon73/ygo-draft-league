-- Per-game notes on a result (YGO-26 follow-up), next to the replay links. Both arrays are indexed by
-- game (element 1 = game 1), with '' for a game that has no link or note. Results reported before
-- this keep their replays as they were (games in order) and have no notes.
alter table match_report add column game_notes text[] not null default '{}';
