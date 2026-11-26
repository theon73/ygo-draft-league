package com.ygoleague.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ygoleague.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/dev/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Bob"))
                .andReturn().getRequest().getSession();

        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(jsonPath("$.user.displayName").value("Bob"))
                .andExpect(jsonPath("$.user.identity").value("dev:bob"))
                .andExpect(jsonPath("$.user.admin").value(false));
        // Same permission checks as production: Bob isn't an admin.
        mvc.perform(post("/api/admin/catalog/sync").with(csrf()).session(session)).andExpect(status().isForbidden());
    }

    @Test
    void unknownUserAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(post("/api/dev/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"mallory\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"alice\"}"))
                .andExpect(status().isForbidden());
    }
}
