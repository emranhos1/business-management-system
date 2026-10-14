package com.eh.bms.repository;

import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import org.hibernate.Session;

import java.util.Optional;

/** Database access for {@code users}. */
public class UserRepository {

    public boolean existsByRole(Session session, UserRole role) {
        return session.createSelectionQuery("select count(u) from User u where u.role = :role", Long.class)
                .setParameter("role", role)
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
