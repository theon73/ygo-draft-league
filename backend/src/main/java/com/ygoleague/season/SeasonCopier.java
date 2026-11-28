package com.ygoleague.season;

import com.ygoleague.season.rules.SeasonRulesDocument;
import com.ygoleague.season.rules.SeasonRulesDocument.CalendarRules;
import com.ygoleague.season.rules.SeasonRulesDocument.DraftRules;
import com.ygoleague.season.rules.SeasonRulesService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts a new season from an earlier one of the same league (YGO-32's "start from"). Each part is
 * optional, and nothing that belongs to the old season's run comes along:
 * <ul>
 *   <li><b>Players</b>: who plays (with their seats, team names and season avatars) and the season's
 *       commissioners. The seats are a starting point; the commissioner draws the order again.
 *   <li><b>Rules</b>: format, competition, transactions, results and playoffs, without the dates
 *       (start date, week start).
 *   <li><b>Draft settings</b>: order, rounds, timer and the Forbidden-card switch, without the
 *       planned start.
 *   <li><b>Card pool</b>: the old season's final pool, exceptions and per-card flags included, as the
 *       new season's starting pool (logged as generated from a copy). Regenerating it later works as
 *       usual from the new cutoff.
 * </ul>
 * The caller has checked that both seasons are in the same league and the caller owns it.
 */
@Component
class SeasonCopier {

    record Parts(boolean players, boolean rules, boolean draft, boolean pool) {}

    private final SeasonRulesService rules;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    SeasonCopier(SeasonRulesService rules, JdbcClient jdbc, ObjectMapper json) {
        this.rules = rules;
        this.jdbc = jdbc;
        this.json = json;
    }

    void copy(Season from, Season to, Parts parts, long userId) {
        if (parts.players()) {
            jdbc.sql("""
                            insert into season_player (season_id, league_id, league_member_id, seat, team_name, avatar_card_id, avatar_passcode)
                            select :to, league_id, league_member_id, seat, team_name, avatar_card_id, avatar_passcode
                            from season_player where season_id = :from""")
                    .param("to", to.getId()).param("from", from.getId()).update();
            jdbc.sql("""
                            insert into season_commissioner (season_id, league_id, league_member_id)
                            select :to, league_id, league_member_id from season_commissioner where season_id = :from""")
                    .param("to", to.getId()).param("from", from.getId()).update();
        }

        if (parts.rules() || parts.draft()) {
            SeasonRulesDocument old = rules.latest(from);
            SeasonRulesDocument doc = parts.rules()
                    // The weeks' plan carries over (a fixed week count); the dates and week start don't.
                    ? old.withCalendar(new CalendarRules(null, old.calendar().weeks(), null))
                    : SeasonRulesDocument.EMPTY;
            DraftRules d = old.draft();
            doc = doc.withDraft(parts.draft()
                    ? new DraftRules(d.type(), d.order(), d.rounds(), null, d.forbiddenDraftable(), d.pickTimer())
                    : DraftRules.EMPTY);
            if (!doc.equals(SeasonRulesDocument.EMPTY)) rules.seed(to, doc, userId);
        }

        if (parts.pool()) {
            int total = jdbc.sql("""
                            insert into pool_card (season_id, league_id, card_id, source, copies, soft_banned, requires_ownership, max_playable, reason)
                            select :to, league_id, card_id, source, copies, soft_banned, requires_ownership, max_playable, reason
                            from pool_card where season_id = :from and removed_in_week is null""")
                    .param("to", to.getId()).param("from", from.getId()).update();
            if (total > 0) {
                java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
                java.time.LocalDate cutoff = rules.latest(from).format().tcgCutoff();
                if (cutoff != null) detail.put("cutoff", cutoff.toString());   // what the pool summary shows
                detail.put("copiedFrom", from.getName());
                detail.put("total", total);
                jdbc.sql("""
                                insert into pool_change (season_id, kind, effective_from_week, detail, created_by)
                                values (:to, 'GENERATED', 0, cast(:detail as jsonb), :u)""")
                        .param("to", to.getId())
                        .param("detail", json.writeValueAsString(detail))
                        .param("u", userId).update();
            }
        }
    }
}
