-- Monster tags (YGO-14): the frame, summon and ability words of a monster's type line, e.g.
-- Shining Flare Wingman = {Effect, Fusion}, Junk Synchron = {Effect, Tuner}, Gemini Elf = {Normal}.
-- Taken from YGOPRODeck's "typeline" (whose first entry is the monster type, e.g. Warrior), which
-- the stored raw record already carries. A generated column, so every catalog sync keeps it right
-- without code changes. Spells, traps and tokens get an empty array.

create function card_monster_tags(raw jsonb) returns text[]
    language sql immutable as $$
        select coalesce(array_agg(t order by t), '{}')
        from jsonb_array_elements_text(coalesce(raw -> 'typeline', '[]'::jsonb)) t
        where t in ('Normal', 'Effect', 'Ritual', 'Fusion', 'Synchro', 'Xyz', 'Link', 'Pendulum',
                    'Tuner', 'Flip', 'Union', 'Gemini', 'Spirit', 'Toon')
    $$;

alter table card add column monster_tags text[] not null generated always as (card_monster_tags(raw)) stored;
create index card_monster_tags_idx on card using gin (monster_tags);
