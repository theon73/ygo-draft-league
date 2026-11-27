-- Roster avatars (YGO-30): a card's art stands for a player.
--
-- Each league member has a default avatar, used in every season; a season player can override it for
-- that season. Both are nullable (no avatar: initials), so existing members and seasons are unchanged.
-- The passcode picks which of the card's artworks; it must be one of the card's passcodes (checked
-- by the service, since an array membership can't be a foreign key).
alter table league_member add column avatar_card_id bigint references card (id);
alter table league_member add column avatar_passcode bigint;
alter table league_member add constraint league_member_avatar_check check ((avatar_card_id is null) = (avatar_passcode is null));

alter table season_player add column avatar_card_id bigint references card (id);
alter table season_player add column avatar_passcode bigint;
alter table season_player add constraint season_player_avatar_check check ((avatar_card_id is null) = (avatar_passcode is null));
