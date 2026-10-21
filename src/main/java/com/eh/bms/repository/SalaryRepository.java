package com.eh.bms.repository;

import com.eh.bms.model.SalaryPayment;
import com.eh.bms.model.SalaryProcessing;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database access for {@code salary_processing} (month sheets) and {@code salary_payments}. */
public class SalaryRepository {

    public Optional<SalaryProcessing> findSheet(Session session, long userId, int year, int month) {
        return session.createSelectionQuery("from SalaryProcessing s where s.user.id = :user"
                        + " and s.year = :year and s.month = :month", SalaryProcessing.class)
                .setParameter("user", userId)
                .setParameter("year", year)
                .setParameter("month", month)
                .uniqueResultOptional();
    }

    /** One user's sheets, newest month first. */
    public List<SalaryProcessing> sheetsOf(Session session, long userId) {
        return session.createSelectionQuery("from SalaryProcessing s where s.user.id = :user"
                        + " order by s.year desc, s.month desc", SalaryProcessing.class)
                .setParameter("user", userId)
                .getResultList();
    }

    /** Every user's sheet of one month, with the user loaded. */
    public List<SalaryProcessing> sheetsOfMonth(Session session, int year, int month) {
        return session.createSelectionQuery("from SalaryProcessing s join fetch s.user"
                        + " where s.year = :year and s.month = :month", SalaryProcessing.class)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList();
    }

    public void saveSheet(Session session, SalaryProcessing sheet) {
        session.persist(sheet);
    }

    public void deleteSheet(Session session, SalaryProcessing sheet) {
        session.remove(sheet);
    }

    public Optional<SalaryPayment> findPayment(Session session, long id) {
        return Optional.ofNullable(session.find(SalaryPayment.class, id));
    }

    /** Payments by id with the user loaded (names the payment in the account ledger). */
    public Map<Long, SalaryPayment> findPaymentsByIds(Session session, Collection<Long> ids) {
        Map<Long, SalaryPayment> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        session.createSelectionQuery("from SalaryPayment p join fetch p.user where p.id in :ids", SalaryPayment.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(p -> result.put(p.getId(), p));
        return result;
    }

    /** One user's payments, newest first, with the account loaded. */
    public List<SalaryPayment> paymentsOf(Session session, long userId) {
        return session.createSelectionQuery("from SalaryPayment p join fetch p.account where p.user.id = :user"
                        + " order by p.date desc, p.id desc", SalaryPayment.class)
                .setParameter("user", userId)
                .getResultList();
    }

    /** One user's payments for one month, oldest first, with the account loaded. */
    public List<SalaryPayment> paymentsOf(Session session, long userId, int year, int month) {
        return session.createSelectionQuery("from SalaryPayment p join fetch p.account where p.user.id = :user"
                        + " and p.year = :year and p.month = :month order by p.date, p.id", SalaryPayment.class)
                .setParameter("user", userId)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList();
    }

    /** One user's total paid per salary month, leaving out payment {@code excludeId} (may be null). */
    public Map<YearMonth, BigDecimal> paidByMonth(Session session, long userId, Long excludeId) {
        var query = session.createSelectionQuery("select p.year, p.month, sum(p.amount) from SalaryPayment p"
                        + " where p.user.id = :user" + (excludeId == null ? "" : " and p.id <> :id")
                        + " group by p.year, p.month", Object[].class)
                .setParameter("user", userId);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        Map<YearMonth, BigDecimal> result = new HashMap<>();
        query.getResultList().forEach(r -> result.put(YearMonth.of((Integer) r[0], (Integer) r[1]), (BigDecimal) r[2]));
        return result;
    }

    /** Every user's net salary per processed month up to and including {@code year}-{@code month}: user id, month, net. */
    public Map<Long, Map<YearMonth, BigDecimal>> netsUpTo(Session session, int year, int month) {
        return byUserMonth(session.createSelectionQuery("select s.user.id, s.year, s.month, s.netSalary"
                        + " from SalaryProcessing s where s.year < :year or (s.year = :year and s.month <= :month)",
                        Object[].class)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList());
    }

    /** Every user's total paid per salary month up to and including {@code year}-{@code month}. */
    public Map<Long, Map<YearMonth, BigDecimal>> paidUpTo(Session session, int year, int month) {
        return byUserMonth(session.createSelectionQuery("select p.user.id, p.year, p.month, sum(p.amount)"
                        + " from SalaryPayment p where p.year < :year or (p.year = :year and p.month <= :month)"
                        + " group by p.user.id, p.year, p.month", Object[].class)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList());
    }

    private static Map<Long, Map<YearMonth, BigDecimal>> byUserMonth(List<Object[]> rows) {
        Map<Long, Map<YearMonth, BigDecimal>> result = new HashMap<>();
        for (Object[] r : rows) {
            result.computeIfAbsent((Long) r[0], k -> new HashMap<>())
                    .put(YearMonth.of((Integer) r[1], (Integer) r[2]), (BigDecimal) r[3]);
        }
        return result;
    }

    /** Total paid per user id for one month. */
    public Map<Long, BigDecimal> paidByUser(Session session, int year, int month) {
        Map<Long, BigDecimal> result = new HashMap<>();
        session.createSelectionQuery("select p.user.id, sum(p.amount) from SalaryPayment p"
                        + " where p.year = :year and p.month = :month group by p.user.id", Object[].class)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList()
                .forEach(r -> result.put((Long) r[0], (BigDecimal) r[1]));
        return result;
    }

    public void savePayment(Session session, SalaryPayment payment) {
        session.persist(payment);
    }

    public void deletePayment(Session session, SalaryPayment payment) {
        session.remove(payment);
    }
}
