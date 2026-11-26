package com.ygoleague.auth;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/** Signs a MockMvc request in as a {@link CurrentUser}, the same token a real login stores. */
final class AuthTestSupport {

    private AuthTestSupport() {}

    static RequestPostProcessor signedIn(boolean admin) {
        CurrentUser user = new CurrentUser(1L, "Tester", null, "google", "sub-1", admin, null);
        return authentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
    }
}
