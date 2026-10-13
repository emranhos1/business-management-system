package com.eh.bms.repository;

import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import org.hibernate.Session;

/** Database access for {@code users}. */
public class UserRepository {

    public boolean existsByRole(Session session, UserRole role) {
        return session.createSelectionQuery("select count(u) from User u where u.role = :role", Long.class)
                .setParameter("role", role)
                .getSingleResult() > 0;
    }

    public void save(Session session, User user) {
        session.persist(user);
    }
}
