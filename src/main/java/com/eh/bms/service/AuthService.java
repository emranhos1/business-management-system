package com.eh.bms.service;

import com.eh.bms.config.AppConfig;
import com.eh.bms.model.Permission;
import com.eh.bms.model.User;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.PermissionRepository;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.TransactionHelper;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/** Checks login credentials and starts the {@link UserSession}. */
public class AuthService {

    /** Compared against when the username does not exist, so both cases take the same time. */
    private static final String DUMMY_HASH = BCrypt.hashpw("dummy-password", BCrypt.gensalt());

    public enum Status {
        SUCCESS,
        /** Unknown username or wrong password; never tell the user which one. */
        INVALID_CREDENTIALS,
        /** Right password but the user is deactivated. */
        INACTIVE
    }

    /** {@code user} is set only when {@code status} is {@link Status#SUCCESS}. */
    public record LoginResult(Status status, User user) {
    }

    /** The only profile where {@link #devAutoLogin} works. */
    private static final String DEV_PROFILE = "dev";
    private static final Logger LOG = Logger.getLogger(AuthService.class.getName());

    private final UserRepository userRepository = new UserRepository();
    private final PermissionRepository permissionRepository = new PermissionRepository();

    public LoginResult login(String username, String password) {
        Optional<User> found = TransactionHelper.inTransaction(
                session -> userRepository.findByUsername(session, username.trim()));

        if (found.isEmpty()) {
            BCrypt.checkpw(password, DUMMY_HASH);
            return new LoginResult(Status.INVALID_CREDENTIALS, null);
        }
        User user = found.get();
        if (!BCrypt.checkpw(password, user.getPasswordHash())) {
            return new LoginResult(Status.INVALID_CREDENTIALS, null);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return new LoginResult(Status.INACTIVE, null);
        }
        List<Permission> permissions = TransactionHelper.inTransaction(
                session -> permissionRepository.findByRole(session, user.getRole()));
        UserSession.start(user, permissions);
        return new LoginResult(Status.SUCCESS, user);
    }

    /**
     * Development only: logs in as {@code dev.auto-login} (a username in application-dev.yml) without a
     * password, so the app opens straight to the dashboard. Works only when the active profile is
     * {@value #DEV_PROFILE}; stage and prod always show the login screen. False (login screen) when the key is
     * blank or the user is missing or inactive.
     */
    public boolean devAutoLogin() {
        AppConfig config = AppConfig.get();
        if (!DEV_PROFILE.equals(config.getProfile())) {
            return false;
        }
        String username = config.getString("dev.auto-login", "").trim();
        if (username.isEmpty()) {
            return false;
        }
        Optional<User> found = TransactionHelper.inTransaction(session -> userRepository.findByUsername(session, username));
        if (found.isEmpty() || found.get().getStatus() != UserStatus.ACTIVE) {
            LOG.warning("dev.auto-login user not found or inactive: " + username + "; showing the login screen");
            return false;
        }
        User user = found.get();
        List<Permission> permissions = TransactionHelper.inTransaction(
                session -> permissionRepository.findByRole(session, user.getRole()));
        UserSession.start(user, permissions);
        LOG.warning("DEV profile: logged in as '" + username + "' without the login screen (dev.auto-login)");
        return true;
    }
}
