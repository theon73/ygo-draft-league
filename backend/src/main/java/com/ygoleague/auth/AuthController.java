package com.ygoleague.auth;

import com.ygoleague.league.AvatarService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the frontend needs to render the header: who's signed in and which ways to sign in exist. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * @param identity the login identity in {@code provider:subject} form, i.e. what to put in
     *                 {@code APP_ADMINS} to make this user a site admin
     * @param avatar   their profile avatar's artwork (a passcode, YGO-30), or null
     */
    public record SessionUser(String displayName, String avatarUrl, boolean admin, String identity, Long avatar) {}

    /**
     * @param providers sign-in providers that are configured, e.g. {@code ["google"]}
     * @param devLogin  whether the dev "log in as…" switcher is available
     */
    public record Session(SessionUser user, List<String> providers, boolean devLogin) {}

    private final boolean googleEnabled;
    private final boolean devLogin;
    private final AvatarService avatars;

    public AuthController(ObjectProvider<ClientRegistrationRepository> clientRegistrations, Environment env, AvatarService avatars) {
        this.avatars = avatars;
        this.googleEnabled = clientRegistrations.getIfAvailable() != null;
        this.devLogin = env.matchesProfiles(DevLoginController.PROFILE);
    }

    @GetMapping("/session")
    public Session session(@AuthenticationPrincipal CurrentUser user) {
        SessionUser u = user == null ? null : new SessionUser(user.displayName(), user.avatarUrl(), user.admin(),
                user.provider() + ":" + user.subject(), avatars.profileAvatar(user.userId()));
        return new Session(u, googleEnabled ? List.of(GoogleLogin.REGISTRATION_ID) : List.of(), devLogin);
    }
}
