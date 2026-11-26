package com.ygoleague.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ygoleague.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** With a client id configured, the login redirect goes to Google with our /api callback. */
@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false",
        "app.auth.google.client-id=test-client", "app.auth.google.client-secret=test-secret"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class GoogleLoginTest {

    @Autowired MockMvc mvc;

    @Test
    void advertisesGoogleAndRedirectsThroughTheApiCallback() throws Exception {
        mvc.perform(get("/api/auth/session")).andExpect(jsonPath("$.providers[0]").value("google"));
        mvc.perform(get("/api/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com/")))
                .andExpect(header().string("Location", containsString("client_id=test-client")))
                .andExpect(header().string("Location",
                        containsString("redirect_uri=http://localhost/api/login/oauth2/code/google")));
    }
}
