package com.eh.bms.service;

import com.eh.bms.model.Permission;
import com.eh.bms.model.User;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.PermissionRepository;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.TransactionHelper;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.Optional;

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
}
