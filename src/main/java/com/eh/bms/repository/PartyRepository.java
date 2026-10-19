package com.eh.bms.repository;

import com.eh.bms.model.Party;
import com.eh.bms.model.PartyStatus;
import org.hibernate.Session;

import java.util.List;
import java.util.Optional;

/** Database access for {@code parties}. */
public class PartyRepository {

    /**
     * One page of parties with this status, sorted by name; {@code search} (may be blank) matches part of the
     * Bangla or English name or phone.
     */
    public List<Party> page(Session session, PartyStatus status, String search, int offset, int limit) {
        boolean filter = search != null && !search.isBlank();
        var query = session.createSelectionQuery("from Party p" + where(filter) + " order by p.name, p.id", Party.class)
                .setParameter("status", status);
        if (filter) {
            query.setParameter("search", like(search));
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public long count(Session session, PartyStatus status, String search) {
        boolean filter = search != null && !search.isBlank();
        var query = session.createSelectionQuery("select count(p) from Party p" + where(filter), Long.class)
                .setParameter("status", status);
        if (filter) {
            query.setParameter("search", like(search));
        }
        return query.getSingleResult();
    }

    private static String where(boolean search) {
        return " where p.status = :status"
                + (search ? " and (p.name like :search or p.nameEn like :search or p.phone like :search)" : "");
    }

    public Optional<Party> findById(Session session, long id) {
        return Optional.ofNullable(session.find(Party.class, id));
    }

    public Optional<Party> findByPhone(Session session, String phone) {
        return session.createSelectionQuery("from Party p where p.phone = :phone", Party.class)
                .setParameter("phone", phone)
                .uniqueResultOptional();
    }

    /** True if another party (not {@code excludeId}) has this phone number. */
    public boolean existsByPhone(Session session, String phone, Long excludeId) {
        String hql = "select count(p) from Party p where p.phone = :phone" + (excludeId == null ? "" : " and p.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("phone", phone);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }

    /** True if the party has ledger entries (its opening balance is then fixed). */
    public boolean hasLedger(Session session, long id) {
        return count(session, "select count(l) from PartyLedger l where l.party.id = :id", id) > 0;
    }

    /** True if any ledger entry or sale/purchase uses the party. */
    public boolean isUsed(Session session, long id) {
        return hasLedger(session, id)
                || count(session, "select count(t) from BusinessTransaction t where t.party.id = :id", id) > 0;
    }

    public void save(Session session, Party party) {
        session.persist(party);
    }

    public void delete(Session session, Party party) {
        session.remove(party);
    }

    /** {@code %text%} with the LIKE wildcards in the user's text escaped. */
    private static String like(String text) {
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static long count(Session session, String hql, long id) {
        return session.createSelectionQuery(hql, Long.class).setParameter("id", id).getSingleResult();
    }
}
