package com.eh.bms.service;

import com.eh.bms.config.SupportAccount;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.AuditAction;
import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.TransactionHelper;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Registration from the login screen (N1, user decisions): anyone may register; the new user waits as
 * {@link UserStatus#PENDING} and cannot log in until an admin approves it and picks its role
 * ({@link UserService#approve}), or rejects it, which deletes it ({@link UserService#reject}).
 */
public class RegistrationService {

    public enum Result {
        REGISTERED, DUPLICATE_USERNAME
    }

    private final UserRepository repository = new UserRepository();
    private final AuditService auditService = new AuditService();

    /** The password is the user's own, so it is not changed at the first login. */
    public Result register(String name, String username, String password) {
        String login = username.trim();
        // the support team's username stays theirs even while there is no support login
        if (login.equalsIgnoreCase(SupportAccount.USERNAME)) {
            return Result.DUPLICATE_USERNAME;
        }
        return TransactionHelper.inTransaction(session -> {
            if (repository.existsByUsername(session, login, null)) {
                return Result.DUPLICATE_USERNAME;
            }
            User u = new User();
            u.setName(name.trim());
            u.setUsername(login);
            u.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
            // the column needs a role; the admin picks the real one when approving
            u.setRole(UserRole.CASHIER);
            u.setStatus(UserStatus.PENDING);
            u.setMustChangePassword(false);
            repository.save(session, u);
            // Nobody is logged in: no user on the audit row, so a rejected registration can still be deleted
            auditService.log(session, AuditAction.CREATE, AppModule.USER.name(), u.getId(), null,
                    "name=" + u.getName() + ", username=" + u.getUsername() + ", status=" + u.getStatus(), null);
            return Result.REGISTERED;
        });
    }
}
