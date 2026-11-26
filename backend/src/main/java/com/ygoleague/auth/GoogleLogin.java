package com.ygoleague.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * "Sign in with Google", switched on only when {@code GOOGLE_CLIENT_ID} is set (env or
 * {@code backend/.env}). Without it the app runs with no real login, which is fine locally
 * with the dev login.
 *
 * <p>The OAuth endpoints live under {@code /api} ({@code /api/oauth2/authorization/google} and
 * {@code /api/login/oauth2/code/google}) so the Vite dev proxy and the planned production
 * routing (only {@code /api/*} goes to the backend) both cover them. Register the redirect URI
 * with Google as {@code <site origin>/api/login/oauth2/code/google}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${app.auth.google.client-id:}'.isBlank()")
public class GoogleLogin {

    static final String REGISTRATION_ID = "google";

    @Bean
    ClientRegistrationRepository clientRegistrations(
            @Value("${app.auth.google.client-id}") String clientId,
            @Value("${app.auth.google.client-secret:}") String clientSecret) {
        if (clientSecret.isBlank()) throw new IllegalStateException("GOOGLE_CLIENT_ID is set but GOOGLE_CLIENT_SECRET is not");
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .redirectUri("{baseUrl}/api/login/oauth2/code/{registrationId}")
                .build());
    }

    @Bean
    GoogleUserService googleUserService(UserAccounts accounts) {
        return new GoogleUserService(accounts);
    }

    /**
     * Maps the verified Google profile to our user (creating it on first login). The Google
     * {@link OidcUser} is dropped here: only our own {@link CurrentUser} goes into the session.
     */
    static class GoogleUserService extends OidcUserService {

        private final UserAccounts accounts;

        GoogleUserService(UserAccounts accounts) {
            this.accounts = accounts;
        }

        @Override
        public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
            return toCurrentUser(super.loadUser(request));
        }

        CurrentUser toCurrentUser(OidcUser google) {
            if (google.getSubject() == null) {
                throw new OAuth2AuthenticationException(new OAuth2Error("invalid_id_token", "No subject", null));
            }
            return accounts.login(new UserAccounts.LoginProfile(REGISTRATION_ID, google.getSubject(),
                    google.getEmail(), google.getFullName(), google.getPicture()));
        }
    }
}
