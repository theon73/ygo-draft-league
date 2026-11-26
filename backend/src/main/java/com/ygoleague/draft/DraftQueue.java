package com.ygoleague.draft;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonPlayer;
import com.ygoleague.season.SeasonPlayerRepository;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.pool.PoolOwnership;
import com.ygoleague.season.rules.SeasonRulesDocument.DraftRules;
import com.ygoleague.season.rules.SeasonRulesService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A player's private pick queue (YGO-18): the cards they want, in order. Used to plan, and by
 * auto-picks (timer expiry, away mode), which take the first card in it that's still available.
 *
 * <ul>
 *   <li>Only the player sees and edits their queue, not even commissioners: it's their plan.
 *   <li>It can be edited during setup and the draft; it only holds cards in the pool.
 *   <li>A card leaves every queue once all its copies are drafted.
 * </ul>
 */
@Service
public class DraftQueue {

    public static final int MAX_CARDS = 200;

    /** @param available whether it can still be drafted (not taken, soft-banned, Forbidden or removed) */
    public record QueuedCard(long cardId, String name, String cardType, long passcode, boolean available) {}

    /** @param away whether away mode is on: auto-pick from the queue as soon as I'm on the clock */
    public record QueueView(boolean away, List<QueuedCard> cards) {}

    private final SeasonAccess access;
    private final SeasonPlayerRepository players;
    private final SeasonRulesService rules;
    private final JdbcClient jdbc;

    public DraftQueue(SeasonAccess access, SeasonPlayerRepository players, SeasonRulesService rules, JdbcClient jdbc) {
        this.access = access;
        this.players = players;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public QueueView get(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        SeasonPlayer me = me(ref);
        return view(ref.season(), me);
    }

    /** Replaces the queue with these cards, in this order (adding, removing and reordering all go through here). */
    @Transactional
    public QueueView replace(String leagueId, String seasonId, List<Long> cardIds, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        SeasonPlayer me = me(ref);
        editable(ref.season());
        List<Long> cards = new ArrayList<>(new LinkedHashSet<>(cardIds == null ? List.of() : cardIds));
        if (cards.size() > MAX_CARDS) throw bad("A queue holds at most " + MAX_CARDS + " cards");
        if (!cards.isEmpty()) {
            long inPool = jdbc.sql("select count(*) from pool_card where season_id = :s and card_id in (:c) and removed_in_week is null")
                    .param("s", ref.season().getId()).param("c", cards).query(Long.class).single();
            if (inPool != cards.size()) throw bad("Only cards in the pool can be queued");
        }
        jdbc.sql("delete from draft_queue where season_player_id = :p").param("p", me.getId()).update();
        for (int i = 0; i < cards.size(); i++) {
            jdbc.sql("insert into draft_queue (season_player_id, card_id, position) values (:p, :c, :pos)")
                    .param("p", me.getId()).param("c", cards.get(i)).param("pos", i + 1).update();
        }
        return view(ref.season(), me);
    }

    /** Adds a card to the end of the queue (no change if it's already there). */
    @Transactional
    public QueueView add(String leagueId, String seasonId, Long cardId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        SeasonPlayer me = me(ref);
        editable(ref.season());
        if (cardId == null) throw bad("Choose a card to queue");
        boolean inPool = jdbc.sql("select exists (select 1 from pool_card where season_id = :s and card_id = :c and removed_in_week is null)")
                .param("s", ref.season().getId()).param("c", cardId).query(Boolean.class).single();
        if (!inPool) throw bad("Only cards in the pool can be queued");
        long size = jdbc.sql("select count(*) from draft_queue where season_player_id = :p").param("p", me.getId()).query(Long.class).single();
        if (size >= MAX_CARDS) throw bad("A queue holds at most " + MAX_CARDS + " cards");
        jdbc.sql("""
                        insert into draft_queue (season_player_id, card_id, position)
                        values (:p, :c, (select coalesce(max(position), 0) + 1 from draft_queue where season_player_id = :p))
                        on conflict do nothing""")
                .param("p", me.getId()).param("c", cardId).update();
        return view(ref.season(), me);
    }

    // ---- Used by the draft --------------------------------------------------------------------

    /** The first card in a player's queue that can be drafted now. */
    Optional<Long> firstAvailable(Season season, long seasonPlayerId, DraftRules draft) {
        return jdbc.sql("""
                        select q.card_id from draft_queue q
                        join pool_card p on p.season_id = :s and p.card_id = q.card_id
                        left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
                        where q.season_player_id = :p and %s
                        order by q.position limit 1""".formatted(AVAILABLE))
                .param("s", season.getId()).param("p", seasonPlayerId).param("rev", banListRevision(season), java.sql.Types.BIGINT)
                .param("forbiddenOk", draft.forbiddenDraftable())
                .query(Long.class).optional();
    }

    /** Takes a card out of every queue in the season once all its copies are held. */
    void dropIfTaken(Season season, long cardId) {
        jdbc.sql("""
                        delete from draft_queue q using season_player sp
                        where sp.id = q.season_player_id and sp.season_id = :s and q.card_id = :c
                          and (select p.copies <= %s from pool_card p where p.season_id = :s and p.card_id = :c)"""
                        .formatted(PoolOwnership.HELD_COPIES))
                .param("s", season.getId()).param("c", cardId).update();
    }

    // ---- Internals ----------------------------------------------------------------------------

    /** Conditions for pool card p (with ban entry b) to be draftable now; binds :forbiddenOk. */
    static final String AVAILABLE = "p.removed_in_week is null and not p.soft_banned "
            + "and (:forbiddenOk or b.status is distinct from 'FORBIDDEN') and p.copies > " + PoolOwnership.HELD_COPIES;

    private QueueView view(Season season, SeasonPlayer me) {
        // Draft settings can't change after the start, so the live rules match the draft's frozen config.
        DraftRules draft = rules.latest(season).draft();
        boolean away = jdbc.sql("select draft_away from season_player where id = :p").param("p", me.getId()).query(Boolean.class).single();
        List<QueuedCard> cards = jdbc.sql("""
                        select c.id, c.name, c.card_type, c.passcodes[1], coalesce(%s, false)
                        from draft_queue q join card c on c.id = q.card_id
                        left join pool_card p on p.season_id = :s and p.card_id = q.card_id
                        left join ban_list_entry b on b.revision_id = :rev and b.card_id = q.card_id
                        where q.season_player_id = :p
                        order by q.position""".formatted(AVAILABLE))
                .param("s", season.getId()).param("p", me.getId()).param("rev", banListRevision(season), java.sql.Types.BIGINT)
                .param("forbiddenOk", draft.forbiddenDraftable())
                .query((rs, n) -> new QueuedCard(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBoolean(5)))
                .list();
        return new QueueView(away, cards);
    }

    private Long banListRevision(Season season) {
        return rules.latest(season).format().banListRevisionId();
    }

    private SeasonPlayer me(SeasonAccess.SeasonRef ref) {
        return players.findBySeasonIdOrderBySeat(ref.season().getId()).stream()
                .filter(p -> p.getSeat() != null && p.getMember().getId().equals(ref.me().getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only players in this season have a pick queue"));
    }

    private static void editable(Season season) {
        if (season.getStatus() != SeasonStatus.SETUP && season.getStatus() != SeasonStatus.DRAFTING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The draft is over");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
