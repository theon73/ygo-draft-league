-- When the league's week starts (and the previous one ends), in the league's time zone: a day of
-- the week and a local time, e.g. Monday 00:00. Weekly limits, deadlines and "this week's
-- matchups" use it. Seasons will take this as their own boundary when they start (YGO-12), so a
-- later change here never re-buckets an active season's past weeks.
alter table league
    add column week_starts_on text not null default 'MONDAY'
        check (week_starts_on in ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    add column week_starts_at time not null default '00:00';
