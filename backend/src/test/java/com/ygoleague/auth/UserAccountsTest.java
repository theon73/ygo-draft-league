package com.ygoleague.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false",
        "app.auth.admins=google:admin-sub, google:someone-else"})
@Import(PostgresTestConfig.class)
class UserAccountsTest {

    @Autowired UserAccounts accounts;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void firstLoginCreatesTheAccountAndLaterLoginsReuseIt() {
        var first = accounts.login(new UserAccounts.LoginProfile("google", "sub-42", "yugi@example.com", "Yugi Muto", "https://a/1"));
        var again = accounts.login(new UserAccounts.LoginProfile("google", "sub-42", "yugi@example.com", "Renamed At Google", "https://a/2"));

        assertEquals(first.userId(), again.userId());
        assertEquals("Yugi Muto", again.displayName());       // name only taken on first login
        assertEquals("https://a/2", again.avatarUrl());       // avatar follows the provider
        assertEquals(1, jdbc.queryForObject("select count(*) from user_identity where subject = 'sub-42'", Long.class));
        assertFalse(again.admin());
    }

    @Test
    void namelessProfileFallsBackToEmailAndAllowlistGrantsAdmin() {
        var admin = accounts.login(new UserAccounts.LoginProfile("google", "admin-sub", "kaiba@example.com", null, null));
        assertEquals("kaiba", admin.displayName());
        assertTrue(admin.admin());
        assertTrue(admin.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        // The allowlist is per identity: the same subject under another provider isn't an admin.
        assertFalse(accounts.login(new UserAccounts.LoginProfile("dev", "admin-sub", null, "X", null)).admin());
    }
}
