package com.ygoleague.auth;

import static com.ygoleague.auth.AuthTestSupport.signedIn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ygoleague.catalog.CatalogSync;
import com.ygoleague.catalog.CatalogSyncService;
import com.ygoleague.catalog.SyncTrigger;
import com.ygoleague.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Access rules without the dev profile and without Google configured (a fresh checkout). */
@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class SecurityRulesTest {

    @Autowired MockMvc mvc;
    @MockitoBean CatalogSyncService catalogSync;

    @Test
    void signedOutSessionReportsNoUserNoProvidersNoDevLogin() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.providers").isEmpty())
                .andExpect(jsonPath("$.devLogin").value(false));
    }

    @Test
    void sessionShowsTheSignedInUsersIdentity() throws Exception {
        mvc.perform(get("/api/auth/session").with(signedIn(false)))
                .andExpect(jsonPath("$.user.displayName").value("Tester"))
                .andExpect(jsonPath("$.user.identity").value("google:sub-1"))
                .andExpect(jsonPath("$.user.admin").value(false));
    }

    @Test
    void devLoginDoesNotExistWithoutTheDevProfile() throws Exception {
        mvc.perform(post("/api/dev/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"alice\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/dev/users")).andExpect(status().isNotFound());
    }

    @Test
    void publicAndProtectedEndpoints() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/explore/summary")).andExpect(status().isOk());
        mvc.perform(get("/api/leagues")).andExpect(status().isUnauthorized());   // anything else needs a login
    }

    @Test
    void adminEndpointsNeedTheAdminRoleAndCsrf() throws Exception {
        when(catalogSync.sync(any(), anyBoolean())).thenReturn(new CatalogSync(SyncTrigger.MANUAL));

        mvc.perform(post("/api/admin/catalog/sync").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/catalog/sync").with(csrf()).with(signedIn(false))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/catalog/sync").with(signedIn(true))).andExpect(status().isForbidden());  // no CSRF token
        mvc.perform(post("/api/admin/catalog/sync").with(csrf()).with(signedIn(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void logoutReturnsNoContent() throws Exception {
        mvc.perform(post("/api/auth/logout").with(csrf()).with(signedIn(false))).andExpect(status().isNoContent());
    }
}
