package com.ygoleague.api.season;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ygoleague.auth.CurrentUser;
import com.ygoleague.image.CardImageService;
import com.ygoleague.notify.DiscordWebhook;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false", "app.draft.timer.enabled=false", "app.notifications.synchronous=true"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class TradeApiTest {

    static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCD-_0123";
    static final String CALENDAR = "\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"";

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;
    @MockitoBean DiscordWebhook discord;

    AppUser yugi, kaiba, joey;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate trade_message, trade_offer_asset, trade_offer, trade_thread, season_transaction_allowance, season_transaction_line, season_transaction, "
                + "league_notification, draft_timer_change, draft_pause, draft_queue, draft_pick, draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        for (int i = 1; i <= 20; i++) card("Card " + i);
        org.mockito.Mockito.when(discord.post(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DiscordWebhook.Delivery(true, 204, null));
        yugi = users.save(new AppUser("Yugi"));     // the owner: commissions, doesn't play
        kaiba = users.save(new AppUser("Kaiba"));
        joey = users.save(new AppUser("Joey"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        for (AppUser u : List.of(kaiba, joey)) {
            String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
            mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(u))).andExpect(status().isOk());
        }
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");

        // Seats: P1 (placeholder), Kaiba, P3 (placeholder), Joey; 2 rounds of 1 pick.
        long seasonId = jdbc.queryForObject("select id from season", Long.class);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P1', 'MEMBER' from season returning id", Long.class), 1, seasonId);
        seat(jdbc.queryForObject("select id from league_member where display_name = 'Kaiba'", Long.class), 2, seasonId);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P3', 'MEMBER' from season returning id", Long.class), 3, seasonId);
        seat(jdbc.queryForObject("select id from league_member where display_name = 'Joey'", Long.class), 4, seasonId);
        send(put(s("/draft/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]}").andExpect(status().isOk());
    }

    /** Rules, pool and the whole draft: P1 gets Cards 1 and 8, Kaiba 2 and 7, P3 3 and 6, Joey 4 and 5. */
    private void ready(String transactions, String startDate) throws Exception {
        send(put(s("/rules")), yugi, "{\"startDate\":\"" + startDate + "\"," + CALENDAR + transactions + "}").andExpect(status().isOk());
        send(post(s("/pool/generate")), yugi, null).andExpect(status().isOk());
        send(post(s("/draft/start")), yugi, null).andExpect(status().isOk());
        AppUser[] by = {yugi, kaiba, yugi, joey, joey, yugi, kaiba, yugi};
        for (int i = 1; i <= 8; i++) pick(by[i - 1], "Card " + i, i);
    }

    private void ready(String transactions) throws Exception {
        ready(transactions, "2030-01-07");
    }

    @Test
    void offersArePrivateEvenAndExecuteOnAcceptance() throws Exception {
        ready(",\"freeAgencyPerWeek\":1,\"tradesPerWeek\":1");
        notifyOn("TRANSACTIONS");
        String joeyId = memberId("Joey"), kaibaId = memberId("Kaiba"), p1 = memberId("P1");

        String first = read(offer(kaiba, null, joeyId, cards("Card 2"), cards("Card 4"), null, "Card 2 for your Card 4?")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN")), "$.id");
        mvc.perform(get(s("/trades/threads/" + kaibaId)).with(as(joey))).andExpect(status().isOk())
                .andExpect(listAt("$.items[*].type", List.of("OFFER", "MESSAGE")))
                .andExpect(jsonPath("$.items[0].offer.canAccept").value(true))
                .andExpect(listAt("$.items[0].offer.fromGives[*].card.name", List.of("Card 2")))
                .andExpect(jsonPath("$.items[1].message.body").value("Card 2 for your Card 4?"));
        mvc.perform(get(s("/trades")).with(as(joey)))
                .andExpect(listAt("$.threads[*].with", List.of("Kaiba")))
                .andExpect(jsonPath("$.threads[0].awaitingYou").value(1))
                .andExpect(jsonPath("$.mine.tradesLeft").value(1));

        // Private to the two players: the commissioner acts only as placeholders, and sees nothing of it.
        mvc.perform(get(s("/trades")).with(as(yugi)))
                .andExpect(listAt("$.actors[*].name", List.of("P1", "P3")))
                .andExpect(jsonPath("$.threads.length()").value(0));
        mvc.perform(get(s("/trades/threads/" + joeyId + "?as=" + kaibaId)).with(as(yugi))).andExpect(status().isForbidden());
        mvc.perform(get(s("/trades/threads/" + kaibaId + "?as=" + p1)).with(as(yugi))).andExpect(jsonPath("$.items.length()").value(0));
        act(yugi, first, "accept", p1).andExpect(status().isNotFound());

        // Even trades only, with cards the givers hold.
        offer(kaiba, null, joeyId, cards("Card 2", "Card 7"), cards("Card 4"), null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Trades are even")));
        offer(kaiba, null, joeyId, cards("Card 1"), cards("Card 4"), null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Kaiba doesn't hold Card 1"));

        // A counter links to the original; both stay acceptable.
        String counter = read(offer(joey, null, kaibaId, cards("Card 5"), cards("Card 7"), first, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.counterOf").value(first)), "$.id");
        act(joey, counter, "accept", null).andExpect(status().isForbidden());   // it's Kaiba's to accept
        act(kaiba, counter, "accept", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"));
        Assertions.assertTrue(posts().getLast().contains("**Joey** and **Kaiba** traded: Kaiba gets **Card 5**; Joey gets **Card 7**"), posts().getLast());

        mvc.perform(get(s("/rosters")).with(as(kaiba)))
                .andExpect(listAt("$.players[1].cards[*].card.name", List.of("Card 2", "Card 5")))
                .andExpect(listAt("$.players[3].cards[*].card.name", List.of("Card 4", "Card 7")));
        mvc.perform(get(s("/transactions")).with(as(kaiba)))
                .andExpect(jsonPath("$[0].kind").value("TRADE"))
                .andExpect(jsonPath("$[0].player").value("Joey"))
                .andExpect(jsonPath("$[0].counterparty").value("Kaiba"))
                .andExpect(listAt("$[0].added[*].name", List.of("Card 7")))
                .andExpect(listAt("$[0].dropped[*].name", List.of("Card 5")))
                .andExpect(jsonPath("$[0].onBehalfBy").doesNotExist());
        mvc.perform(get(s("/cards/" + cardId("Card 5"))).with(as(kaiba)))
                .andExpect(listAt("$.history[*].source", List.of("DRAFT", "TRADE")))
                .andExpect(jsonPath("$.history[1].from").value("Joey"))
                .andExpect(jsonPath("$.history[1].to").value("Kaiba"))
                .andExpect(jsonPath("$.history[1].onBehalfBy").doesNotExist());

        // The original's cards didn't move, so it's still open, but both have used their one trade.
        act(joey, first, "accept", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Kaiba has no trades left for week 1"));

        // An offer whose card moves elsewhere is cancelled: here, Kaiba drops Card 5 in a claim.
        String stale = read(offer(yugi, memberId("P3"), kaibaId, cards("Card 3"), cards("Card 5"), null, null).andExpect(status().isCreated()), "$.id");
        send(post(s("/free-agency/claims")), kaiba, "{\"claimCardId\":" + cardId("Card 10") + ",\"dropCardId\":" + cardId("Card 5") + "}")
                .andExpect(status().isCreated());
        mvc.perform(get(s("/trades/threads/" + memberId("P3"))).with(as(kaiba)))
                .andExpect(jsonPath("$.items[0].offer.id").value(stale))
                .andExpect(jsonPath("$.items[0].offer.status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].offer.cancelReason").value("Card 5 changed hands"));

        // Declined offers disappear for both players; only the proposer withdraws.
        String declined = read(offer(yugi, p1, joeyId, cards("Card 1"), cards("Card 4"), null, null).andExpect(status().isCreated()), "$.id");
        act(yugi, declined, "withdraw", joeyId).andExpect(status().isForbidden());   // not Yugi's to act as Joey
        act(joey, declined, "withdraw", null).andExpect(status().isForbidden());
        act(joey, declined, "decline", null).andExpect(status().isOk());
        mvc.perform(get(s("/trades/threads/" + p1)).with(as(joey))).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(s("/trades/threads/" + joeyId + "?as=" + p1)).with(as(yugi))).andExpect(jsonPath("$.items.length()").value(0));
        act(joey, declined, "accept", null).andExpect(status().isNotFound());

        // Messages on their own.
        send(post(s("/trades/threads/" + kaibaId + "/messages")), joey, "{\"body\":\"Good trade\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[-1:].message.body").value("Good trade"));
        Assertions.assertEquals(8, jdbc.queryForObject("select count(*) from card_holding", Integer.class));
    }

    @Test
    void allowancesCanBeTradedAndApprovalHoldsTheTrade() throws Exception {
        ready(",\"freeAgencyPerWeek\":1,\"tradesPerWeek\":2,\"commissionerApproval\":true");
        String joeyId = memberId("Joey"), kaibaId = memberId("Kaiba");

        // Kaiba gives his free-agent claim for one of Joey's trades.
        String id = read(offer(kaiba, null, joeyId, "[{\"kind\":\"FREE_AGENCY\"}]", "[{\"kind\":\"TRADE\",\"quantity\":1}]", null, null)
                .andExpect(status().isCreated()), "$.id");
        act(joey, id, "accept", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        // Waiting for approval: it hasn't counted yet. The commissioner now sees it.
        mvc.perform(get(s("/trades")).with(as(kaiba))).andExpect(jsonPath("$.mine.tradesLeft").value(2)).andExpect(jsonPath("$.approvals.length()").value(0));
        mvc.perform(get(s("/trades")).with(as(yugi)))
                .andExpect(jsonPath("$.approvals[0].id").value(id))
                .andExpect(jsonPath("$.approvals[0].canApprove").value(true));
        send(post(s("/trades/offers/" + id + "/approve")), kaiba, null).andExpect(status().isForbidden());
        send(post(s("/trades/offers/" + id + "/approve")), yugi, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"));

        // Kaiba: 2 trades + 1 received - 1 used = 2, no claims. Joey: 2 - 1 given - 1 used = 0, 2 claims.
        mvc.perform(get(s("/trades")).with(as(kaiba))).andExpect(jsonPath("$.mine.tradesLeft").value(2)).andExpect(jsonPath("$.mine.claimsLeft").value(0));
        mvc.perform(get(s("/trades")).with(as(joey))).andExpect(jsonPath("$.mine.tradesLeft").value(0)).andExpect(jsonPath("$.mine.claimsLeft").value(2));
        mvc.perform(get(s("/free-agency")).with(as(joey))).andExpect(jsonPath("$.players[0].left").value(2)).andExpect(jsonPath("$.players[0].limit").value(2));
        mvc.perform(get(s("/transactions")).with(as(joey)))
                .andExpect(listAt("$[0].allowances[*].kind", List.of("FREE_AGENCY", "TRADE")))
                .andExpect(jsonPath("$[0].onBehalfBy").value("Yugi"));

        // Joey can't give more than he has; a rejected trade goes nowhere.
        offer(joey, null, kaibaId, "[{\"kind\":\"FREE_AGENCY\",\"quantity\":3}]", "[]", null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Joey has only 2 free-agent claims left this week"));
        String other = read(offer(kaiba, null, joeyId, cards("Card 2"), cards("Card 4"), null, null), "$.id");
        act(joey, other, "accept", null).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Joey has no trades left for week 1"));
        String rejected = read(offer(kaiba, null, memberId("P3"), cards("Card 2"), cards("Card 3"), null, null), "$.id");
        act(yugi, rejected, "accept", memberId("P3")).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        send(post(s("/trades/offers/" + rejected + "/reject")), yugi, null).andExpect(jsonPath("$.status").value("REJECTED"));
        Assertions.assertEquals("Kaiba", jdbc.queryForObject("""
                select m.display_name from card_holding h join season_player sp on sp.id = h.season_player_id
                join league_member m on m.id = sp.league_member_id join card c on c.id = h.card_id where c.name = 'Card 2'""", String.class));
    }

    @Test
    void unlimitedAllowancesCantBeTradedAndTheDeadlineClosesTrading() throws Exception {
        // Week 1 started this week or last; a deadline of week 0 has passed.
        java.time.LocalDate start = java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).minusWeeks(1);
        ready(",\"tradeDeadlineWeek\":0", start.toString());
        offer(kaiba, null, memberId("Joey"), cards("Card 2"), cards("Card 4"), null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The trade deadline (week 0) has passed"));
        mvc.perform(get(s("/trades")).with(as(kaiba))).andExpect(jsonPath("$.open").value(false));
        int week = JsonPath.read(mvc.perform(get(s("/trades")).with(as(kaiba))).andReturn().getResponse().getContentAsString(), "$.week");
        send(put(s("/rules")), yugi, "{\"startDate\":\"" + start + "\"," + CALENDAR + ",\"effectiveFromWeek\":" + week + "}").andExpect(status().isOk());
        offer(kaiba, null, memberId("Joey"), "[{\"kind\":\"TRADE\"}]", "[]", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trades are unlimited, so they can't be traded"));
    }

    @Test
    void unreadMessagesAreCountedUntilTheConversationIsOpened() throws Exception {
        ready(",\"tradesPerWeek\":1");
        String joeyId = memberId("Joey"), kaibaId = memberId("Kaiba");
        for (String body : List.of("Hi Joey", "Want Card 2?")) {
            send(post(s("/trades/threads/" + joeyId + "/messages")), kaiba, "{\"body\":\"" + body + "\"}").andExpect(status().isCreated());
        }
        // Everywhere Joey looks: the season, the league and its card, the conversation, Needs you.
        mvc.perform(get(s("")).with(as(joey))).andExpect(jsonPath("$.unreadMessages").value(2));
        mvc.perform(get("/api/leagues").with(as(joey))).andExpect(jsonPath("$[0].unread").value(2)).andExpect(jsonPath("$[0].currentSeason.unread").value(2));
        mvc.perform(get(l("")).with(as(joey))).andExpect(jsonPath("$.seasons[0].unread").value(2));
        mvc.perform(get(s("/trades")).with(as(joey))).andExpect(jsonPath("$.threads[0].unread").value(2));
        mvc.perform(get(s("/needs-you")).with(as(joey)))
                .andExpect(jsonPath("$[?(@.kind == 'MESSAGE')].title").value("Kaiba messaged you"))
                .andExpect(jsonPath("$[?(@.kind == 'MESSAGE')].tab").value("trades?with=" + kaibaId));
        mvc.perform(get(s("")).with(as(kaiba))).andExpect(jsonPath("$.unreadMessages").value(0));   // your own don't count

        // An offer counts too; opening the conversation reads everything.
        offer(kaiba, null, joeyId, cards("Card 2"), cards("Card 4"), null, null).andExpect(status().isCreated());
        mvc.perform(get(s("")).with(as(joey))).andExpect(jsonPath("$.unreadMessages").value(3));
        mvc.perform(get(s("/trades/threads/" + kaibaId)).with(as(joey))).andExpect(status().isOk());
        mvc.perform(get(s("")).with(as(joey))).andExpect(jsonPath("$.unreadMessages").value(0));
        mvc.perform(get(s("/trades")).with(as(joey))).andExpect(jsonPath("$.threads[0].unread").value(0));
    }

    // ---- helpers ----

    private ResultActions offer(AppUser user, String as, String to, String give, String get, String counterOf, String message) throws Exception {
        return send(post(s("/trades/offers")), user, "{" + (as == null ? "" : "\"as\":\"" + as + "\",") + "\"toMemberId\":\"" + to + "\","
                + "\"give\":" + give + ",\"get\":" + get
                + (counterOf == null ? "" : ",\"counterOf\":\"" + counterOf + "\"")
                + (message == null ? "" : ",\"message\":\"" + message + "\"") + "}");
    }

    private String cards(String... names) {
        return "[" + String.join(",", java.util.Arrays.stream(names).map(n -> "{\"kind\":\"CARD\",\"cardId\":" + cardId(n) + "}").toList()) + "]";
    }

    private ResultActions act(AppUser user, String offer, String action, String as) throws Exception {
        return send(post(s("/trades/offers/" + offer + "/" + action)), user, as == null ? "{}" : "{\"as\":\"" + as + "\"}");
    }

    private String memberId(String name) throws Exception {
        return JsonPath.read(mvc.perform(get(s("")).with(as(yugi))).andReturn().getResponse().getContentAsString(),
                "$.participants[?(@.displayName == '" + name + "')].memberId").toString().replaceAll("[\\[\\]\"]", "");
    }

    private void notifyOn(String... events) throws Exception {
        String list = String.join(",", java.util.Arrays.stream(events).map(e -> "\"" + e + "\"").toList());
        send(put(l("/notifications")), yugi, "{\"webhookUrl\":\"" + WEBHOOK + "\",\"events\":[" + list + "]}").andExpect(status().isOk());
    }

    private List<String> posts() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(discord, org.mockito.Mockito.atLeast(0))
                .post(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), captor.capture(), org.mockito.ArgumentMatchers.any());
        return captor.getAllValues();
    }

    private void seat(long memberId, int seat, long seasonId) {
        jdbc.update("insert into season_player (season_id, league_id, league_member_id, seat) select ?, league_id, ?, ? from league_member where id = ?",
                seasonId, memberId, seat, memberId);
    }

    private void pick(AppUser user, String card, int overallPick) throws Exception {
        send(post(s("/draft/picks")), user, "{\"cardId\":" + cardId(card) + ",\"overallPick\":" + overallPick + "}").andExpect(status().isOk());
    }

    private long cardId(String name) {
        return jdbc.queryForObject("select id from card where name = ?", Long.class, name);
    }

    private void card(String name) {
        jdbc.update("""
                insert into card (passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (array[(select coalesce(max(id), 0) + 1000 from card)]::bigint[], ?, 'Effect Monster', 'effect', 'Fiend', 'text', '2002-03-08', '{}', 'h')""",
                name);
    }

    private String l(String path) { return "/api/leagues/" + league + path; }
    private String s(String path) { return l("/seasons/" + season + path); }

    private static ResultMatcher listAt(String path, List<?> expected) {
        return result -> Assertions.assertEquals(expected, JsonPath.read(result.getResponse().getContentAsString(), path), path);
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private ResultActions send(MockHttpServletRequestBuilder req, AppUser user, String body) throws Exception {
        req.with(csrf()).with(as(user));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private static RequestPostProcessor as(AppUser user) {
        CurrentUser principal = new CurrentUser(user.getId(), user.getDisplayName(), null, "dev", "u" + user.getId(), false);
        return authentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "dev"));
    }
}
