package com.ygoleague.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Log in as…" for local development. Only exists under the {@code dev} profile (absent, not
 * disabled, everywhere else), and {@link DevProfileGuard} refuses to start {@code dev} against
 * anything that looks like production.
 *
 * <p>It signs in as an existing {@code dev} identity (seeded by {@link DevSeed}) and stores the
 * same {@link CurrentUser} principal and token type a Google login does, so everything after
 * login, permission checks included, behaves exactly as in production.
 */
@RestController
@RequestMapping("/api/dev")
@Profile(DevLoginController.PROFILE)
public class DevLoginController {

    public static final String PROFILE = "dev";
    static final String PROVIDER = "dev";

    public record DevUser(String subject, String displayName, boolean admin) {}

    public record LoginRequest(String subject) {}

    private final UserAccounts accounts;
    private final AdminAllowlist admins;
    private final JdbcClient jdbc;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public DevLoginController(UserAccounts accounts, AdminAllowlist admins, JdbcClient jdbc) {
        this.accounts = accounts;
        this.admins = admins;
        this.jdbc = jdbc;
    }

    @GetMapping("/users")
    public List<DevUser> users() {
        return jdbc.sql("""
                        select i.subject, u.display_name from user_identity i join app_user u on u.id = i.user_id
                        where i.provider = :p order by u.display_name
                        """)
                .param("p", PROVIDER)
                .query((rs, n) -> new DevUser(rs.getString(1), rs.getString(2), admins.isAdmin(PROVIDER, rs.getString(1))))
                .list();
    }

    @PostMapping("/login")
    public AuthController.SessionUser login(@RequestBody LoginRequest body, HttpServletRequest request,
                                            HttpServletResponse response) {
        CurrentUser user = accounts.loginExisting(PROVIDER, body.subject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No dev user " + body.subject()));

        // New session id on login, as the real login does (session fixation protection).
        request.getSession(true);
        request.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), PROVIDER));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        return new AuthController.SessionUser(user.displayName(), user.avatarUrl(), user.admin(),
                user.provider() + ":" + user.subject());
    }
}
