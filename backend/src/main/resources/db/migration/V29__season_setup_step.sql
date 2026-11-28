-- Guided setup (YGO-32): where a season's setup wizard is, so leaving halfway is safe and the
-- Overview can offer "Continue setup (step N of 6)". Null: the wizard isn't in use (every season
-- created before it, and any season once its setup is finished).
alter table season add column setup_step int check (setup_step between 1 and 6);
