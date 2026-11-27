-- League notifications (YGO-21): a Discord webhook and which events to post to it. A table of its
-- own so the webhook URL (a secret: anyone holding it can post to the channel) stays out of the
-- league row that every member's pages read. events holds NotificationEvent names; later events
-- (trades, free agency, results) are added in code without a schema change.
create table league_notification (
    league_id           bigint      primary key references league (id),
    discord_webhook_url text,
    events              text[]      not null default '{}',
    updated_at          timestamptz not null default now(),
    updated_by          bigint      references app_user (id)
);
