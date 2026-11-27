-- YGO-29: remove the source's fake "???" card.
--
-- YGOPRODeck lists a "???" Spell Card (no Konami id, TCG date 1990-01-01) as a stand-in for unknown
-- cards in its tournament deck profiles. Earlier syncs imported it, so every dated pool included it.
-- The parser now skips it; this removes the copy already in the catalog, with everything that points
-- at it: pool rows, pool-change log entries, queue entries, ban list entries, prints and text versions.
--
-- If anyone has drafted, claimed or traded it, that history is kept (the card row stays, so nothing
-- dangles) and only its undrafted pool rows go. In practice nobody has.

create temporary table placeholder_card on commit drop as
select id from card where name = '???' and konami_id is null;

create temporary table placeholder_kept on commit drop as
select p.id from placeholder_card p
where exists (select 1 from draft_pick d where d.card_id = p.id)
   or exists (select 1 from season_transaction_line t where t.card_id = p.id)
   or exists (select 1 from trade_offer_asset a where a.card_id = p.id);

delete from draft_queue where card_id in (select id from placeholder_card);
delete from pool_card
where card_id in (select id from placeholder_card)
  and not exists (select 1 from draft_pick d where d.card_id = pool_card.card_id and d.season_id = pool_card.season_id);

delete from pool_change where card_id in (select id from placeholder_card where id not in (select id from placeholder_kept));
delete from ban_list_entry where card_id in (select id from placeholder_card where id not in (select id from placeholder_kept));
delete from card_print where card_id in (select id from placeholder_card where id not in (select id from placeholder_kept));
delete from card_text_version where card_id in (select id from placeholder_card where id not in (select id from placeholder_kept));
delete from card where id in (select id from placeholder_card where id not in (select id from placeholder_kept));
