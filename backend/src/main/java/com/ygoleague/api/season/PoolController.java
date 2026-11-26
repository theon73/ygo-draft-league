package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.pool.PoolService;
import com.ygoleague.season.pool.PoolService.AddRequest;
import com.ygoleague.season.pool.PoolService.ArchetypeCount;
import com.ygoleague.season.pool.PoolService.Candidate;
import com.ygoleague.season.pool.PoolService.CardPage;
import com.ygoleague.season.pool.PoolService.ChangeView;
import com.ygoleague.season.pool.PoolService.PoolQuery;
import com.ygoleague.season.pool.PoolService.FlagsUpdate;
import com.ygoleague.season.pool.PoolService.PoolCardView;
import com.ygoleague.season.pool.PoolService.PoolSummary;
import com.ygoleague.season.pool.PoolService.RemoveRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A season's card pool. Rules for each action are in {@link PoolService}. */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}/pool")
public class PoolController {

    private final PoolService pool;

    public PoolController(PoolService pool) {
        this.pool = pool;
    }

    @GetMapping
    public PoolSummary summary(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return pool.summary(leagueId, seasonId, user);
    }

    /**
     * The pool, filtered and sorted. Every parameter is optional; see {@link PoolQuery}. Spring binds
     * the query string onto the record by name (e.g. {@code ?kind=SPELL&race=Quick-Play&sort=RELEASE}).
     */
    @GetMapping("/cards")
    public CardPage cards(@PathVariable String leagueId, @PathVariable String seasonId, PoolQuery query,
                          @AuthenticationPrincipal CurrentUser user) {
        return pool.cards(leagueId, seasonId, query, user);
    }

    /** The attributes, types and archetypes present in the pool, for the filter dropdowns. */
    @GetMapping("/facets")
    public PoolService.Facets facets(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return pool.facets(leagueId, seasonId, user);
    }

    /** Catalog cards matching a name, for adding to the pool by hand. */
    @GetMapping("/candidates")
    public List<Candidate> candidates(@PathVariable String leagueId, @PathVariable String seasonId, @RequestParam String q,
                                      @AuthenticationPrincipal CurrentUser user) {
        return pool.candidates(leagueId, seasonId, q, user);
    }

    @GetMapping("/archetypes")
    public List<ArchetypeCount> archetypes(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return pool.archetypes(leagueId, seasonId, user);
    }

    @GetMapping("/changes")
    public List<ChangeView> changes(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return pool.changes(leagueId, seasonId, user);
    }

    /** Builds or rebuilds the dated pool from the rules' TCG cutoff (setup only). */
    @PostMapping("/generate")
    public PoolSummary generate(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return pool.generate(leagueId, seasonId, user);
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolCardView add(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody AddRequest body,
                            @AuthenticationPrincipal CurrentUser user) {
        return pool.add(leagueId, seasonId, body, user);
    }

    @DeleteMapping("/cards/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable long cardId,
                       @RequestBody(required = false) RemoveRequest body, @AuthenticationPrincipal CurrentUser user) {
        pool.remove(leagueId, seasonId, cardId, body, user);
    }

    @PatchMapping("/cards/{cardId}")
    public PoolCardView flags(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable long cardId,
                              @RequestBody FlagsUpdate body, @AuthenticationPrincipal CurrentUser user) {
        return pool.setFlags(leagueId, seasonId, cardId, body, user);
    }

    @PatchMapping("/archetypes/{archetype}")
    public PoolSummary archetypeFlags(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String archetype,
                                      @RequestBody FlagsUpdate body, @AuthenticationPrincipal CurrentUser user) {
        return pool.setArchetypeFlags(leagueId, seasonId, archetype, body, user);
    }
}
