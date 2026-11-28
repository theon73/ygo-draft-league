package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.transaction.TradeService;
import com.ygoleague.season.transaction.TradeService.ActRequest;
import com.ygoleague.season.transaction.TradeService.MessageRequest;
import com.ygoleague.season.transaction.TradeService.OfferRequest;
import com.ygoleague.season.transaction.TradeService.OfferView;
import com.ygoleague.season.transaction.TradeService.ThreadView;
import com.ygoleague.season.transaction.TradeService.TradesView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Trades: one private conversation per pair of players, with offers, counteroffers and messages.
 * {@code as} picks who the caller acts as (themselves by default; a commissioner may act as a
 * placeholder). Rules for each action are in {@link TradeService}.
 */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}/trades")
public class TradeController {

    private final TradeService trades;

    public TradeController(TradeService trades) {
        this.trades = trades;
    }

    @GetMapping
    public TradesView overview(@PathVariable String leagueId, @PathVariable String seasonId, @RequestParam(required = false) String as,
                               @AuthenticationPrincipal CurrentUser user) {
        return trades.overview(leagueId, seasonId, as, user);
    }

    /** Live updates (YGO-35): a server-sent-event stream of "one of your conversations changed" signals (see MessageEvents). */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return trades.subscribe(leagueId, seasonId, user);
    }

    /** The conversation with another player (empty if there's none yet). */
    @GetMapping("/threads/{memberId}")
    public ThreadView thread(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String memberId,
                             @RequestParam(required = false) String as, @AuthenticationPrincipal CurrentUser user) {
        return trades.thread(leagueId, seasonId, memberId, as, user);
    }

    @PostMapping("/threads/{memberId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreadView message(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String memberId,
                              @RequestBody MessageRequest body, @AuthenticationPrincipal CurrentUser user) {
        return trades.message(leagueId, seasonId, memberId, body, user);
    }

    @PostMapping("/offers")
    @ResponseStatus(HttpStatus.CREATED)
    public OfferView propose(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody OfferRequest body,
                             @AuthenticationPrincipal CurrentUser user) {
        return trades.propose(leagueId, seasonId, body, user);
    }

    @PostMapping("/offers/{offerId}/accept")
    public OfferView accept(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String offerId,
                            @RequestBody(required = false) ActRequest body, @AuthenticationPrincipal CurrentUser user) {
        return trades.accept(leagueId, seasonId, offerId, body, user);
    }

    @PostMapping("/offers/{offerId}/decline")
    public OfferView decline(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String offerId,
                             @RequestBody(required = false) ActRequest body, @AuthenticationPrincipal CurrentUser user) {
        return trades.decline(leagueId, seasonId, offerId, body, user);
    }

    @PostMapping("/offers/{offerId}/withdraw")
    public OfferView withdraw(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String offerId,
                              @RequestBody(required = false) ActRequest body, @AuthenticationPrincipal CurrentUser user) {
        return trades.withdraw(leagueId, seasonId, offerId, body, user);
    }

    /** Commissioners: approve or reject an accepted trade (when the season's rules ask for approval). */
    @PostMapping("/offers/{offerId}/approve")
    public OfferView approve(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String offerId,
                             @AuthenticationPrincipal CurrentUser user) {
        return trades.approve(leagueId, seasonId, offerId, user);
    }

    @PostMapping("/offers/{offerId}/reject")
    public OfferView reject(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String offerId,
                            @AuthenticationPrincipal CurrentUser user) {
        return trades.reject(leagueId, seasonId, offerId, user);
    }
}
