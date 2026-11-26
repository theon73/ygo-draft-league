package com.ygoleague.banlist;

import com.ygoleague.league.League;
import com.ygoleague.user.AppUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading ban lists and managing league-owned custom lists. Every change writes a new revision;
 * existing revisions are never modified. Callers are responsible for checking that the acting
 * user is a commissioner of {@code leagueId} (authorization arrives with login).
 */
@Service
public class BanListService {

    private final BanListRepository lists;
    private final BanListRevisionRepository revisions;
    private final BanListEntryRepository entries;

    public BanListService(BanListRepository lists, BanListRevisionRepository revisions,
                          BanListEntryRepository entries) {
        this.lists = lists;
        this.revisions = revisions;
        this.entries = entries;
    }

    /** Card id -> status for one revision. Cards not present are unlimited. */
    @Transactional(readOnly = true)
    public Map<Long, BanStatus> entries(long revisionId) {
        Map<Long, BanStatus> map = new HashMap<>();
        for (BanListEntry e : entries.findByRevisionId(revisionId)) map.put(e.getCardId(), e.getStatus());
        return map;
    }

    @Transactional(readOnly = true)
    public BanListRevision latestRevision(long banListId) {
        return revisions.findFirstByBanListIdOrderByRevisionDesc(banListId)
                .orElseThrow(() -> new NoSuchElementException("Ban list " + banListId + " has no revisions"));
    }

    /** Lists a league can use: every official list plus its own custom lists. */
    @Transactional(readOnly = true)
    public List<BanList> visibleTo(long leagueId) {
        List<BanList> result = new java.util.ArrayList<>(lists.findByLeagueIsNullOrderByEffectiveDateDesc());
        result.addAll(lists.findByLeagueIdOrderByName(leagueId));
        return result;
    }

    /**
     * Creates a custom list for a league, empty or as a copy of a revision of any list the league
     * can see (an official list or one of its own).
     *
     * @param copyFromRevisionId null to start from scratch
     * @return the new list's first revision
     */
    @Transactional
    public BanListRevision createCustom(League league, String name, Long copyFromRevisionId, AppUser by) {
        Map<Long, BanStatus> content = Map.of();
        BanListRevision basedOn = null;
        if (copyFromRevisionId != null) {
            basedOn = revisions.findById(copyFromRevisionId)
                    .filter(r -> r.getBanList().isVisibleTo(league.getId()))
                    .orElseThrow(() -> new NoSuchElementException("No such ban list revision"));
            content = entries(copyFromRevisionId);
        }
        BanList list = lists.save(BanList.custom(league, name, by));
        String note = basedOn == null ? "Created from scratch"
                : "Copied from " + basedOn.getBanList().getName() + " r" + basedOn.getRevision();
        return writeRevision(list, 1, basedOn, content, note, by);
    }

    /**
     * Applies changes to a league's custom list as a new revision. A null status removes the card
     * (unlimited). If the result equals the latest revision, nothing is written and that revision
     * is returned.
     */
    @Transactional
    public BanListRevision revise(long leagueId, long banListId, Map<Long, BanStatus> changes,
                                  String note, AppUser by) {
        BanList list = lists.findById(banListId)
                .filter(l -> !l.isOfficial() && l.getLeague().getId().equals(leagueId))
                .orElseThrow(() -> new NoSuchElementException("No such custom ban list in this league"));
        BanListRevision latest = latestRevision(banListId);
        Map<Long, BanStatus> content = BanListContent.apply(entries(latest.getId()), changes);
        if (BanListContent.hash(content).equals(latest.getContentHash())) return latest;
        return writeRevision(list, latest.getRevision() + 1, null, content, note, by);
    }

    /** Writes a revision and its entries. Shared with the official importer. */
    BanListRevision writeRevision(BanList list, int number, BanListRevision basedOn,
                                  Map<Long, BanStatus> content, String note, AppUser by) {
        BanListRevision rev = revisions.save(
                new BanListRevision(list, number, basedOn, BanListContent.hash(content), note, by));
        entries.saveAll(content.entrySet().stream()
                .map(e -> new BanListEntry(rev.getId(), e.getKey(), e.getValue()))
                .toList());
        return rev;
    }
}
