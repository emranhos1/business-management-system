package com.eh.bms.repository;

import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.model.UserStatus;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;

import java.util.List;
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

    public Optional<User> findById(Session session, long id) {
        return Optional.ofNullable(session.find(User.class, id));
    }

    /** True if another user (not {@code excludeId}) already has this username. */
    public boolean existsByUsername(Session session, String username, Long excludeId) {
        var query = session.createSelectionQuery("select count(u) from User u where u.username = :username"
                        + (excludeId == null ? "" : " and u.id <> :id"), Long.class)
                .setParameter("username", username);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }

    /**
     * Users with this status sorted by name; {@code search} (may be blank) matches name, username, mobile or NID. The support
     * team's hidden login is never listed.
     */
    public List<User> page(Session session, UserStatus status, String search, int offset, int limit) {
        boolean filter = search != null && !search.isBlank();
        var query = session.createSelectionQuery("from User u" + where(filter) + " order by u.name, u.id", User.class)
                .setParameter("status", status)
                .setParameter("hidden", UserRole.SUPER_ADMIN);
        if (filter) {
            query.setParameter("search", like(search));
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public long count(Session session, UserStatus status, String search) {
        boolean filter = search != null && !search.isBlank();
        var query = session.createSelectionQuery("select count(u) from User u" + where(filter), Long.class)
                .setParameter("status", status)
                .setParameter("hidden", UserRole.SUPER_ADMIN);
        if (filter) {
            query.setParameter("search", like(search));
        }
        return query.getSingleResult();
    }

    /** Every user sorted by name (e.g. the audit log's user filter), without the support login. */
    public List<User> all(Session session) {
        return session.createSelectionQuery("from User u where u.role <> :hidden order by u.name, u.id", User.class)
                .setParameter("hidden", UserRole.SUPER_ADMIN)
                .getResultList();
    }

    /** How many active admins there are: the last one can never be switched off or demoted. */
    public long countActiveAdmins(Session session) {
        return session.createSelectionQuery("select count(u) from User u where u.role = :role and u.status = :active",
                        Long.class)
                .setParameter("role", UserRole.ADMIN)
                .setParameter("active", UserStatus.ACTIVE)
                .getSingleResult();
    }

    /**
     * True once the user appears in the audit log, has a salary sheet or payment, or made a salary payment; such a
     * user is made inactive, not deleted.
     */
    public boolean hasHistory(Session session, long userId) {
        return count(session, "select count(a) from AuditLog a where a.user.id = :id", userId) > 0
                || count(session, "select count(s) from SalaryProcessing s where s.user.id = :id", userId) > 0
                || count(session, "select count(p) from SalaryPayment p where p.user.id = :id or p.createdBy.id = :id",
                userId) > 0;
    }

    /** Locks the user row until the transaction ends (salary sheet and payments of one user). */
    public Optional<User> lock(Session session, long id) {
        return Optional.ofNullable(session.find(User.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    /** True if another user (not {@code excludeId}) has this NID. */
    public boolean existsByNid(Session session, String nid, Long excludeId) {
        var query = session.createSelectionQuery("select count(u) from User u where u.nid = :nid"
                        + (excludeId == null ? "" : " and u.id <> :id"), Long.class)
                .setParameter("nid", nid);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }

    /** Active users with a salary set, sorted by name (the month's salary sheet); never the support login. */
    public List<User> activeWithSalary(Session session) {
        return session.createSelectionQuery("from User u where u.status = :active and u.role <> :hidden"
                        + " and u.salaryType is not null and u.baseSalary is not null order by u.name, u.id", User.class)
                .setParameter("active", UserStatus.ACTIVE)
                .setParameter("hidden", UserRole.SUPER_ADMIN)
                .getResultList();
    }

    public List<User> findByIds(Session session, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return session.createSelectionQuery("from User u where u.id in :ids order by u.name, u.id", User.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    private static long count(Session session, String hql, long id) {
        return session.createSelectionQuery(hql, Long.class).setParameter("id", id).getSingleResult();
    }

    public void save(Session session, User user) {
        session.persist(user);
    }

    public void delete(Session session, User user) {
        session.remove(user);
    }

    private static String where(boolean search) {
        return " where u.status = :status and u.role <> :hidden"
                + (search ? " and (u.name like :search or u.username like :search or u.phone like :search"
                + " or u.nid like :search)" : "");
    }

    private static String like(String search) {
        return "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
