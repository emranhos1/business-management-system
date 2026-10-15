package com.eh.bms.repository;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Permission;
import com.eh.bms.model.UserRole;
import org.hibernate.Session;

import java.util.List;

/** Database access for {@code permissions}. */
public class PermissionRepository {

    public boolean exists(Session session, UserRole role, AppModule module) {
        return session.createSelectionQuery(
                        "select count(p) from Permission p where p.role = :role and p.module = :module", Long.class)
                .setParameter("role", role)
                .setParameter("module", module)
                .getSingleResult() > 0;
    }

    public List<Permission> findByRole(Session session, UserRole role) {
        return session.createSelectionQuery("from Permission p where p.role = :role", Permission.class)
                .setParameter("role", role)
                .getResultList();
    }

    public void save(Session session, Permission permission) {
        session.persist(permission);
    }
}
