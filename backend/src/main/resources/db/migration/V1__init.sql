-- Phase 0 schema: just enough to prove the stack. See PLANNING.md §3 for the full model.

create table player (
    id           bigserial primary key,
    display_name text        not null unique,
    created_at   timestamptz not null default now()
);

create table season (
    id           bigserial primary key,
    name         text        not null,
    format_date  date        not null,          -- card pool cutoff (TCG release date)
    rules        jsonb       not null default '{}'::jsonb,
    created_at   timestamptz not null default now()
);

create table season_player (
    id         bigserial primary key,
    season_id  bigint not null references season (id),
    player_id  bigint not null references player (id),
    seat       int,
    team_name  text,
    unique (season_id, player_id),
    unique (season_id, seat)
);

-- Seed the current league so the UI has something to show.
insert into player (display_name) values
    ('Theo'), ('Oli'), ('Zach'), ('Owen'), ('Kyle'),
    ('Lance'), ('Greg'), ('Tako'), ('Andre'), ('Axo');
