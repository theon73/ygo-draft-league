-- A season's draft (YGO-15). One row per season, created when a commissioner starts the draft.
-- config is the authoritative record of how it runs: the draft settings and the seat order as they
-- were at the start, so later edits elsewhere can't rewrite it. result is the frozen final board,
-- written when the last pick is made (YGO-16); the picks themselves are append-only events.
create table draft (
    season_id    bigint      primary key references season (id),
    type         text        not null check (type in ('SNAKE')),
    config       jsonb       not null,
    started_at   timestamptz not null default now(),
    started_by   bigint      not null references app_user (id),
    completed_at timestamptz,
    result       jsonb,
    check ((completed_at is null) = (result is null))
);
