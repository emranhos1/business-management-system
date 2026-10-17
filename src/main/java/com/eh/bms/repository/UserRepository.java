package com.eh.bms.repository;

import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.model.UserStatus;
import org.hibernate.Session;

import java.util.Optional;

/** Database access for {@code users}. */
public class UserRepository {

    /** True if at least one user of this role can log in (status ACTIVE). */
    public boolean existsActiveByRole(Session session, UserRole role) {
        return session.createSelectionQuery("select count(u) from User u where u.role = :role and u.status = :active",
                        Long.class)
                .setParameter("role", role)
                .setParameter("active", UserStatus.ACTIVE)
                .getSingleResult() > 0;
    }

    public Optional<User> findByUsername(Session session, String username) {
        return session.createSelectionQuery("from User u where u.username = :username", User.class)
                .setParameter("username", username)
                .uniqueResultOptional();
    }

    public void save(Session session, User user) {
        session.persist(user);
    }
}
