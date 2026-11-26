package com.ygoleague.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * Session-cookie auth for the SPA.
 *
 * <ul>
 *   <li>Signed-out API calls get 401 (never a redirect to a login page); the frontend decides
 *       what to show.
 *   <li>CSRF uses Spring's SPA mode: the {@code XSRF-TOKEN} cookie is readable by JavaScript and
 *       every POST/PUT/DELETE echoes it in the {@code X-XSRF-TOKEN} header (see
 *       {@code frontend/src/api/client.ts}).
 *   <li>{@code /api/admin/**} needs {@code ROLE_ADMIN}, which only {@link AdminAllowlist} grants.
 *   <li>After a Google login nothing Google-issued is kept: the access token is discarded
 *       ({@link DiscardGoogleTokens}) and the session holds only our {@link CurrentUser}.
 * </ul>
 *
 * <p>Only for the web app: the CLI tasks (syncCatalog, importBanLists) run without a web server.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                            ObjectProvider<GoogleLogin.GoogleUserService> googleUsers) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Invite previews work signed out (the code itself is the secret); accepting needs a login.
                        .requestMatchers(HttpMethod.GET, "/api/invites/*").permitAll()
                        .requestMatchers("/api/health", "/api/auth/session", "/api/explore/**", "/api/dev/**",
                                "/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
                    .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
                    .userInfoEndpoint(u -> u.oidcUserService(googleUsers.getObject()))
                    .authorizedClientRepository(new DiscardGoogleTokens())
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/?login=failed"));
        }
        return http.build();
    }
}
