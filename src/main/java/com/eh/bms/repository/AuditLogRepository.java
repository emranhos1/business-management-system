package com.eh.bms.repository;

import com.eh.bms.model.AuditLog;
import org.hibernate.Session;
import org.hibernate.query.SelectionQuery;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only access to {@code audit_log} for the audit log viewer (17.4). */
public class AuditLogRepository {

    /**
     * What to show: changes made {@code from} (inclusive) to {@code to} (exclusive), by one user ({@code userId}
     * null = anyone) in one module or record kind ({@code module} null = all). A null date is no limit.
     */
    public record Filter(LocalDateTime from, LocalDateTime to, Long userId, String module) {
    }

    /** Newest first; the user is loaded with each row. */
    public List<AuditLog> page(Session session, Filter filter, int offset, int limit) {
        var query = session.createSelectionQuery("from AuditLog a left join fetch a.user" + where(filter)
                + " order by a.timestamp desc, a.id desc", AuditLog.class);
        bind(query, filter);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public long count(Session session, Filter filter) {
        var query = session.createSelectionQuery("select count(a) from AuditLog a" + where(filter), Long.class);
        bind(query, filter);
        return query.getSingleResult();
    }

    /** Every module / record kind that appears in the log, by name. */
    public List<String> modules(Session session) {
        return session.createSelectionQuery("select distinct a.module from AuditLog a order by a.module", String.class)
                .getResultList();
    }

    private static String where(Filter f) {
        StringBuilder hql = new StringBuilder(" where 1 = 1");
        if (f.from() != null) {
            hql.append(" and a.timestamp >= :from");
        }
        if (f.to() != null) {
            hql.append(" and a.timestamp < :to");
        }
        if (f.userId() != null) {
            hql.append(" and a.user.id = :user");
        }
        if (f.module() != null) {
            hql.append(" and a.module = :module");
        }
        return hql.toString();
    }

    private static void bind(SelectionQuery<?> query, Filter f) {
        if (f.from() != null) {
            query.setParameter("from", f.from());
        }
        if (f.to() != null) {
            query.setParameter("to", f.to());
        }
        if (f.userId() != null) {
            query.setParameter("user", f.userId());
        }
        if (f.module() != null) {
            query.setParameter("module", f.module());
        }
    }
}
