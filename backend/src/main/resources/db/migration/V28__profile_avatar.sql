-- Profile avatars (YGO-30): a card's art for the account itself, shown in the header and used by
-- every league and season the person hasn't given an avatar of their own. Nullable: no change for
-- existing users.
alter table app_user add column avatar_card_id bigint references card (id);
alter table app_user add column avatar_passcode bigint;
alter table app_user add constraint app_user_avatar_check check ((avatar_card_id is null) = (avatar_passcode is null));
