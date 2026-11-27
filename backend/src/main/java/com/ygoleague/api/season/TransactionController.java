package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.transaction.FreeAgencyService;
import com.ygoleague.season.transaction.FreeAgencyService.ClaimRequest;
import com.ygoleague.season.transaction.FreeAgencyService.FreeAgencyView;
import com.ygoleague.season.transaction.TransactionFeed.TransactionView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Free agency and the season's transaction feed. Rules for each action are in {@link FreeAgencyService}. */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}")
public class TransactionController {

    private final FreeAgencyService freeAgency;

    public TransactionController(FreeAgencyService freeAgency) {
        this.freeAgency = freeAgency;
    }

    /** Whether claims are open, the week, and the claims left for each player the caller can claim for. */
    @GetMapping("/free-agency")
    public FreeAgencyView status(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return freeAgency.status(leagueId, seasonId, user);
    }

    /** Claims a free agent and drops a card, as one transaction. */
    @PostMapping("/free-agency/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView claim(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody ClaimRequest body,
                                 @AuthenticationPrincipal CurrentUser user) {
        return freeAgency.claim(leagueId, seasonId, body, user);
    }

    /** Executed transactions, newest first: the league's activity feed. */
    @GetMapping("/transactions")
    public List<TransactionView> transactions(@PathVariable String leagueId, @PathVariable String seasonId,
                                              @AuthenticationPrincipal CurrentUser user) {
        return freeAgency.transactions(leagueId, seasonId, user);
    }
}
