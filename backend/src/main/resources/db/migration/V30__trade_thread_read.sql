-- Unread trade messages (YGO-34): when each player last read each of their trade conversations.
-- Unread = the other player's messages and offers to you since then. Everything that already
-- exists counts as read, so nobody opens the site to a pile of old "unread" conversations.
create table trade_thread_read (
    thread_id        bigint      not null references trade_thread (id),
    season_player_id bigint      not null references season_player (id),
    last_read_at     timestamptz not null,
    primary key (thread_id, season_player_id)
);

insert into trade_thread_read (thread_id, season_player_id, last_read_at)
select id, player_low_id, now() from trade_thread
union all
select id, player_high_id, now() from trade_thread;
