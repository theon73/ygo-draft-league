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
 * <p>It holds only our own data: nothing issued by Google (no ID token, access token or claims)
 * is kept after login. It implements {@link OidcUser} only because Spring's OAuth2 login requires
 * the principal to be one, so the OIDC accessors return empty values.
 *
 * <p>Sessions are stored Java-serialized in Postgres. Changing this record's fields makes existing
 * sessions unreadable; {@link SessionConfig} turns that into "signed out" rather than an error.
 *
 * @param provider the identity used for this login, e.g. {@code google} or {@code dev}
 * @param subject  that provider's stable id for the user
 */
public record CurrentUser(long userId, String displayName, String avatarUrl, String provider, String subject,
                          boolean admin) implements OidcUser, Serializable {

    @Serial private static final long serialVersionUID = 2L;

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
        return Map.of("sub", subject);
    }

    @Override
    public Map<String, Object> getClaims() {
        return Map.of("sub", subject);
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return null;
    }

    @Override
    public OidcIdToken getIdToken() {
        return null;
    }
}
