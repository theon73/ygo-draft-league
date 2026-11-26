package com.ygoleague.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ygoleague.support.PostgresTestConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The dev login end to end, including the session store: requests carry the real SESSION cookie,
 * as a browser would, and the session lives in Postgres (Spring Session JDBC).
 */
@SpringBootTest(properties = {"app.banlists.import-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(PostgresTestConfig.class)
class DevLoginTest {

    @Autowired MockMvc mvc;
    @Autowired DevSeed seed;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedIsIdempotent() {
        seed.run(new DefaultApplicationArguments());
        assertEquals(4, jdbc.queryForObject("select count(*) from user_identity where provider = 'dev'", Long.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from league where name = ?", Long.class, DevSeed.LEAGUE_NAME));
        assertEquals(5, jdbc.queryForObject("""
                select count(*) from league_member m join league l on l.id = m.league_id where l.name = ?""",
                Long.class, DevSeed.LEAGUE_NAME));
    }

    @Test
    void listsDevUsersWithAdminFlag() throws Exception {
        mvc.perform(get("/api/dev/users"))
                .andExpect(jsonPath("$[0].subject").value("alice"))
                .andExpect(jsonPath("$[0].admin").value(true))
                .andExpect(jsonPath("$[1].admin").value(false));
        mvc.perform(get("/api/auth/session")).andExpect(jsonPath("$.devLogin").value(true));
    }

    @Test
    void loginCreatesASessionWithTheSamePrincipalAsRealLogin() throws Exception {
        Cookie session = login("bob");

        mvc.perform(get("/api/auth/session").cookie(session))
                .andExpect(jsonPath("$.user.displayName").value("Bob"))
                .andExpect(jsonPath("$.user.identity").value("dev:bob"))
                .andExpect(jsonPath("$.user.admin").value(false));
        // Same permission checks as production: Bob isn't an admin.
        mvc.perform(post("/api/admin/catalog/sync").with(csrf()).cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    void sessionIsStoredInPostgresWithLongLivedLockedDownCookie() throws Exception {
        MvcResult result = loginResult("carol");
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("SESSION="), setCookie);
        assertTrue(setCookie.contains("Max-Age=7776000"), setCookie);   // 90 days
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie);

        String userId = jdbc.queryForObject("""
                select u.id::text from app_user u join user_identity i on i.user_id = u.id
                where i.provider = 'dev' and i.subject = 'carol'""", String.class);
        assertEquals(2_592_000, jdbc.queryForObject(   // 30 days idle
                "select max_inactive_interval from spring_session where principal_name = ?", Integer.class, userId));
    }

    @Test
    void unreadableSessionDataMeansSignedOutNotAnError() throws Exception {
        Cookie session = login("dave");
        // Simulate a session written by an older, incompatible deploy.
        jdbc.update("update spring_session_attributes set attribute_bytes = ? where attribute_name = 'SPRING_SECURITY_CONTEXT'",
                (Object) new byte[] {1, 2, 3});

        mvc.perform(get("/api/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void logoutDeletesTheStoredSession() throws Exception {
        Cookie session = login("alice");
        long before = jdbc.queryForObject("select count(*) from spring_session", Long.class);

        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(session)).andExpect(status().isNoContent());

        assertEquals(before - 1, jdbc.queryForObject("select count(*) from spring_session", Long.class));
        mvc.perform(get("/api/auth/session").cookie(session)).andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void unknownUserAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(post("/api/dev/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"mallory\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"alice\"}"))
                .andExpect(status().isForbidden());
    }

    private Cookie login(String subject) throws Exception {
        Cookie cookie = loginResult(subject).getResponse().getCookie("SESSION");
        assertNotNull(cookie, "login should set the SESSION cookie");
        return cookie;
    }

    private MvcResult loginResult(String subject) throws Exception {
        return mvc.perform(post("/api/dev/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"" + subject + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }
}
