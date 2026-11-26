package com.ygoleague.auth;

import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import com.ygoleague.user.UserIdentity;
import com.ygoleague.user.UserIdentityRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns a verified login identity into an app user, creating the account on first login. */
@Service
public class UserAccounts {

    /** What the identity provider told us about the person. */
    public record LoginProfile(String provider, String subject, String email, String name, String avatarUrl) {}

    private final AppUserRepository users;
    private final UserIdentityRepository identities;
    private final AdminAllowlist admins;

    public UserAccounts(AppUserRepository users, UserIdentityRepository identities, AdminAllowlist admins) {
        this.users = users;
        this.identities = identities;
        this.admins = admins;
    }

    /**
     * Signs in with an identity, creating the user and identity on first login. The display name
     * is only taken from the provider the first time, so a name the user changes in the app sticks;
     * the avatar follows the provider.
     */
    @Transactional
    public CurrentUser login(LoginProfile p) {
        AppUser user = identities.findByProviderAndSubject(p.provider(), p.subject())
                .map(UserIdentity::getUser)
                .orElseGet(() -> {
                    AppUser created = new AppUser(displayName(p));
                    created = users.save(created);
                    identities.save(new UserIdentity(created, p.provider(), p.subject(), p.email()));
                    return created;
                });
        if (p.avatarUrl() != null) user.setAvatarUrl(p.avatarUrl());
        return principal(user, p.provider(), p.subject());
    }

    /** Signs in with an identity that must already exist (the dev login never creates accounts). */
    @Transactional(readOnly = true)
    public Optional<CurrentUser> loginExisting(String provider, String subject) {
        return identities.findByProviderAndSubject(provider, subject)
                .map(i -> principal(i.getUser(), provider, subject));
    }

    private CurrentUser principal(AppUser user, String provider, String subject) {
        return new CurrentUser(user.getId(), user.getDisplayName(), user.getAvatarUrl(), provider, subject,
                admins.isAdmin(provider, subject));
    }

    private static String displayName(LoginProfile p) {
        if (p.name() != null && !p.name().isBlank()) return p.name().strip();
        if (p.email() != null && p.email().contains("@")) return p.email().substring(0, p.email().indexOf('@'));
        return "Player";
    }
}
