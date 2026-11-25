-- League URLs use an opaque public id encoded from the primary key (see PublicIds), so the
-- stored slug and its uniqueness/format constraints are no longer needed.
alter table league drop column slug;
