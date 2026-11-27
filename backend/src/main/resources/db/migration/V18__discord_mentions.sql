-- Opt-in Discord pings (YGO-21). A member who wants to be @mentioned enters their Discord user id
-- (the number from Discord's "Copy User ID"; entered by hand, not linked or verified) on their
-- account, once for all leagues, and opts in per league. Webhooks can only ping by id: a typed
-- "@name" is plain text to Discord.
alter table app_user add column discord_user_id text check (discord_user_id ~ '^[0-9]{17,20}$');
alter table league_member add column discord_ping boolean not null default false;
