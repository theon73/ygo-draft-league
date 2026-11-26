package com.ygoleague.auth;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup if the {@code dev} profile (which enables the password-less dev login) is active
 * alongside anything that looks like production: the {@code prod} profile, or a database that
 * isn't on this machine.
 */
@Component
@Profile(DevLoginController.PROFILE)
class DevProfileGuard {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    DevProfileGuard(Environment env, DataSource dataSource) throws SQLException {
        String url;
        try (Connection c = dataSource.getConnection()) {
            url = c.getMetaData().getURL();
        }
        check(env.matchesProfiles("prod"), url);
    }

    static void check(boolean prodProfile, String jdbcUrl) {
        if (prodProfile) {
            throw new IllegalStateException("The 'dev' profile must never run together with 'prod'");
        }
        String host = host(jdbcUrl);
        if (!LOCAL_HOSTS.contains(host)) {
            throw new IllegalStateException("The 'dev' profile only runs against a local database, not " + host);
        }
    }

    /** Host of a {@code jdbc:postgresql://host:port/db} URL, lower-cased; empty if it can't be read. */
    static String host(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) return "";
        try {
            String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
