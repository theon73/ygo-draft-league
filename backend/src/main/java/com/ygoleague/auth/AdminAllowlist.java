package com.ygoleague.auth;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Site admins, as login identities ({@code provider:subject}, e.g. {@code google:1043...}) from
 * {@code app.auth.admins} (env {@code APP_ADMINS}, comma-separated). Never stored in the database
 * and never settable through the API, so a bug in user editing can't make anyone an admin.
 * A signed-in user can find their own identity on {@code GET /api/auth/session}.
 */
@Component
public class AdminAllowlist {

    private final Set<String> identities;

    public AdminAllowlist(@Value("${app.auth.admins:}") String admins) {
        this.identities = Arrays.stream(admins.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String provider, String subject) {
        return identities.contains(provider + ":" + subject);
    }
}
