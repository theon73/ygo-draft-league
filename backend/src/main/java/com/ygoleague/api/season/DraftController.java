package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.draft.DraftService;
import com.ygoleague.draft.DraftService.DraftUpdate;
import com.ygoleague.draft.DraftService.PickRequest;
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

/** A season's draft: its settings, board and readiness, starting it, and making picks. Rules are in {@link DraftService}. */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}/draft")
public class DraftController {

    private final DraftService drafts;

    public DraftController(DraftService drafts) {
        this.drafts = drafts;
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
}
