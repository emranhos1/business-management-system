package com.eh.bms.repository;

import com.eh.bms.model.PartyLedger;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Database access for {@code party_ledger}. Net = debit - credit (+ party owes us more, - we owe more). */
public class PartyLedgerRepository {

    /** What we gave the party (debit) and what we got from them (credit). */
    public record Totals(BigDecimal debit, BigDecimal credit) {
        public BigDecimal net() {
            return debit.subtract(credit);
        }
    }

    public Totals totals(Session session, long partyId) {
        Object[] row = session.createSelectionQuery(
                        "select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)"
                                + " from PartyLedger l where l.party.id = :id", Object[].class)
                .setParameter("id", partyId)
                .getSingleResult();
        return new Totals((BigDecimal) row[0], (BigDecimal) row[1]);
    }

    /** Net per party; parties with no entries are missing from the map. */
    public Map<Long, BigDecimal> netByParty(Session session, Collection<Long> partyIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (partyIds.isEmpty()) {
            return result;
        }
        List<Object[]> rows = session.createSelectionQuery(
                        "select l.party.id, sum(l.debit - l.credit) from PartyLedger l"
                                + " where l.party.id in :ids group by l.party.id", Object[].class)
                .setParameter("ids", partyIds)
                .getResultList();
        for (Object[] row : rows) {
            result.put((Long) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    /** One page of a party's entries, newest first (by date, then entry order). */
    public List<PartyLedger> page(Session session, long partyId, int offset, int limit) {
        return session.createSelectionQuery(
                        "from PartyLedger l where l.party.id = :id order by l.date desc, l.id desc", PartyLedger.class)
                .setParameter("id", partyId)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session, long partyId) {
        return session.createSelectionQuery(
                        "select count(l) from PartyLedger l where l.party.id = :id", Long.class)
                .setParameter("id", partyId)
                .getSingleResult();
    }

    /** Net of the entries that come after this one in (date, id) order. */
    public BigDecimal netAfter(Session session, long partyId, LocalDate date, long entryId) {
        BigDecimal net = session.createSelectionQuery(
                        "select sum(l.debit - l.credit) from PartyLedger l where l.party.id = :id"
                                + " and (l.date > :date or (l.date = :date and l.id > :entryId))", BigDecimal.class)
                .setParameter("id", partyId)
                .setParameter("date", date)
                .setParameter("entryId", entryId)
                .getSingleResult();
        return net == null ? BigDecimal.ZERO : net;
    }

    /** Entries by id, with their party loaded (e.g. to name the party in an account ledger). */
    public Map<Long, PartyLedger> findByIds(Session session, Collection<Long> ids) {
        Map<Long, PartyLedger> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        session.createSelectionQuery("from PartyLedger l join fetch l.party where l.id in :ids", PartyLedger.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(l -> result.put(l.getId(), l));
        return result;
    }

    public void save(Session session, PartyLedger entry) {
        session.persist(entry);
    }
}
