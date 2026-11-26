package com.ygoleague.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The signed-in user, as stored in the session. The same class backs both a real Google login
 * and the dev login, so code after login can't tell them apart. Controllers take it with
 * {@code @AuthenticationPrincipal CurrentUser user}.
 *
 * <p>It implements {@link OidcUser} only because Spring's OAuth2 login requires the principal
 * to be one. For a dev login the OIDC parts (id token, claims) are empty.
 *
 * @param provider the identity used for this login, e.g. {@code google} or {@code dev}
 * @param subject  that provider's stable id for the user
 */
public record CurrentUser(long userId, String displayName, String avatarUrl, String provider, String subject,
                          boolean admin, OidcUser oidc) implements OidcUser, Serializable {

    @Serial private static final long serialVersionUID = 1L;

    static final String ROLE_USER = "ROLE_USER";
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin
                ? List.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority(ROLE_ADMIN))
                : List.of(new SimpleGrantedAuthority(ROLE_USER));
    }

    /** Spring's "name" for the principal: our user id, not the provider's. */
    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return oidc == null ? Map.of("sub", subject) : oidc.getAttributes();
    }

    @Override
    public Map<String, Object> getClaims() {
        return oidc == null ? Map.of("sub", subject) : oidc.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return oidc == null ? null : oidc.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return oidc == null ? null : oidc.getIdToken();
    }

    /** Leaves the OIDC tokens and claims out of logs. */
    @Override
    public String toString() {
        return "CurrentUser[" + userId + " " + provider + ":" + subject + (admin ? " admin" : "") + "]";
    }
}
