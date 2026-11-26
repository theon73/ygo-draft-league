package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.draft.DraftQueue;
import com.ygoleague.draft.DraftQueue.QueueView;
import com.ygoleague.draft.DraftService;
import com.ygoleague.draft.DraftService.AwayRequest;
import com.ygoleague.draft.DraftService.DraftUpdate;
import com.ygoleague.draft.DraftService.PickRequest;
import java.util.List;
import com.ygoleague.draft.DraftService.DraftView;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A season's draft: its settings, board and readiness, starting it, making picks, live updates, and
 * each player's private queue and away mode. Rules are in {@link DraftService} and {@link DraftQueue}.
 */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}/draft")
public class DraftController {

    public record QueueReplace(List<Long> cardIds) {}

    public record QueueAdd(Long cardId) {}

    private final DraftService drafts;
    private final DraftQueue queue;

    public DraftController(DraftService drafts, DraftQueue queue) {
        this.drafts = drafts;
        this.queue = queue;
    }

    @GetMapping
    public DraftView get(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return drafts.get(leagueId, seasonId, user);
    }

    /** Live updates: a server-sent-event stream of "the draft changed" signals (see DraftEvents). */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return drafts.subscribe(leagueId, seasonId, user);
    }

    @PutMapping("/settings")
    public DraftView settings(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody DraftUpdate body,
                              @AuthenticationPrincipal CurrentUser user) {
        return drafts.saveSettings(leagueId, seasonId, body, user);
    }

    @PostMapping("/start")
    public DraftView start(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return drafts.start(leagueId, seasonId, user);
    }

    @PostMapping("/picks")
    public DraftView pick(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody PickRequest body,
                          @AuthenticationPrincipal CurrentUser user) {
        return drafts.pick(leagueId, seasonId, body, user);
    }

    /** The caller's own queue and away mode (players only). */
    @GetMapping("/queue")
    public QueueView queue(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return queue.get(leagueId, seasonId, user);
    }

    /** Replaces the caller's queue: the cards in order (reordering and removing go through here). */
    @PutMapping("/queue")
    public QueueView replaceQueue(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody QueueReplace body,
                                  @AuthenticationPrincipal CurrentUser user) {
        return queue.replace(leagueId, seasonId, body.cardIds(), user);
    }

    /** Adds a card to the end of the caller's queue. */
    @PostMapping("/queue")
    public QueueView addToQueue(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody QueueAdd body,
                                @AuthenticationPrincipal CurrentUser user) {
        return queue.add(leagueId, seasonId, body.cardId(), user);
    }

    @PutMapping("/away")
    public QueueView away(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody AwayRequest body,
                          @AuthenticationPrincipal CurrentUser user) {
        return drafts.setAway(leagueId, seasonId, body, user);
    }

    // Commissioner controls (YGO-19)

    @PostMapping("/pause")
    public DraftView pause(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return drafts.pause(leagueId, seasonId, user);
    }

    @PostMapping("/resume")
    public DraftView resume(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return drafts.resume(leagueId, seasonId, user);
    }

    /** Undoes the latest pick still in effect (repeatable). */
    @PostMapping("/undo")
    public DraftView undo(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return drafts.undo(leagueId, seasonId, user);
    }

    /** Takes a draft with no picks back to setup. */
    @PostMapping("/unstart")
    public DraftView unstart(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return drafts.unstart(leagueId, seasonId, user);
    }
}
