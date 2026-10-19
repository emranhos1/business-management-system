package com.eh.bms.repository;

import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.PartyLedger;
import com.eh.bms.model.TransactionStatus;
import com.eh.bms.model.TransactionType;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only queries for the reports (Stage 16), grouped by day; the service groups them further. */
public class ReportRepository {

    /**
     * Confirmed sales or purchases dated {@code from}..{@code to}, one row per day:
     * {@code [date, count, grand total, returned, paid, due]}.
     */
    public List<Object[]> billsByDate(Session session, TransactionType type, LocalDate from, LocalDate to) {
        return session.createSelectionQuery("select t.date, count(t), sum(t.grandTotal),"
                        + " sum(coalesce(t.returnedAmount, 0)), sum(coalesce(t.paidAmount, 0)),"
                        + " sum(coalesce(t.dueAmount, 0)) from BusinessTransaction t"
                        + " where t.type = :type and t.status = :confirmed and t.date >= :from and t.date <= :to"
                        + " group by t.date order by t.date", Object[].class)
                .setParameter("type", type)
                .setParameter("confirmed", TransactionStatus.CONFIRMED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** Expenses dated {@code from}..{@code to}, one row per day: {@code [date, count, total]}. */
    public List<Object[]> expensesByDate(Session session, LocalDate from, LocalDate to) {
        return session.createSelectionQuery("select e.date, count(e), sum(e.amount) from Expense e"
                        + " where e.date >= :from and e.date <= :to group by e.date order by e.date", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** Money into ({@code CREDIT}) or out of ({@code DEBIT}) all accounts per day, transfers left out. */
    public Map<LocalDate, BigDecimal> moneyByDate(Session session, LocalDate from, LocalDate to,
                                                  AccountTransactionType type) {
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        session.createSelectionQuery("select t.date, sum(t.amount) from AccountTransaction t"
                        + " where t.date >= :from and t.date <= :to and t.type = :type"
                        + " and (t.source is null or t.source <> :transfer) group by t.date", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("type", type)
                .setParameter("transfer", AccountTransactionSource.TRANSFER)
                .getResultList()
                .forEach(r -> result.put((LocalDate) r[0], (BigDecimal) r[1]));
        return result;
    }

    /** Every ledger entry of a party in date order (then entry order), so a running balance can be worked out. */
    public List<PartyLedger> ledgerOf(Session session, long partyId) {
        return session.createSelectionQuery("from PartyLedger l where l.party.id = :party order by l.date, l.id",
                        PartyLedger.class)
                .setParameter("party", partyId)
                .getResultList();
    }

    /** What each party was given and what it gave, entries dated {@code from}..{@code to}: {@code [debit, credit]}. */
    public Map<Long, BigDecimal[]> partyTotalsBetween(Session session, LocalDate from, LocalDate to) {
        Map<Long, BigDecimal[]> result = new HashMap<>();
        session.createSelectionQuery("select l.party.id, sum(l.debit), sum(l.credit) from PartyLedger l"
                        + " where l.date >= :from and l.date <= :to group by l.party.id", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .forEach(r -> result.put((Long) r[0], new BigDecimal[]{(BigDecimal) r[1], (BigDecimal) r[2]}));
        return result;
    }
}
