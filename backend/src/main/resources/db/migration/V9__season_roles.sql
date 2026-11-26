-- Roles move from the league to the season (decided 2026-11-25).
--
-- A league is a group of people: each league_member is an OWNER (exactly one per league) or a
-- MEMBER. Per season, some members are commissioners (season_commissioner) and some are players
-- (season_player); a member who isn't playing a season can still follow it. The league owner is
-- implicitly a commissioner of every season, so isn't stored in season_commissioner.
--
-- Data migration: existing league commissioners other than the owner become commissioners of
-- their league's existing seasons; everyone else becomes a member.

create table season_commissioner (
    season_id        bigint      not null,
    league_id        bigint      not null,
    league_member_id bigint      not null,
    created_at       timestamptz not null default now(),
    primary key (season_id, league_member_id),
    foreign key (season_id, league_id)        references season (id, league_id),
    foreign key (league_member_id, league_id) references league_member (id, league_id)
);

-- 1. Who owns each league: its creator if still a member, otherwise its longest-standing
--    commissioner (only possible if the creator was demoted and then removed).
create temporary table league_owner on commit drop as
select distinct on (m.league_id) m.league_id, m.id as member_id
from league_member m
join league l on l.id = m.league_id
where m.user_id is not null
order by m.league_id,
         (m.user_id = l.created_by) desc,
         (m.role = 'COMMISSIONER') desc,
         m.created_at, m.id;

-- 2. Other commissioners keep commissioning the seasons that already exist.
insert into season_commissioner (season_id, league_id, league_member_id)
select s.id, s.league_id, m.id
from season s
join league_member m on m.league_id = s.league_id and m.role = 'COMMISSIONER'
where m.id not in (select member_id from league_owner);

-- 3. League roles become OWNER / MEMBER.
alter table league_member drop constraint league_member_role_check;
update league_member set role = case when id in (select member_id from league_owner) then 'OWNER' else 'MEMBER' end;
alter table league_member add constraint league_member_role_check check (role in ('OWNER', 'MEMBER'));
alter table league_member alter column role set default 'MEMBER';
create unique index league_member_one_owner on league_member (league_id) where role = 'OWNER';

-- 4. Open invites now just mean "join the league" (as a member).
alter table league_invite drop constraint league_invite_role_check;
update league_invite set role = 'MEMBER' where role is not null;
alter table league_invite add constraint league_invite_role_check check (role = 'MEMBER');
