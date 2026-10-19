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

    public Optional<SalaryProcessing> findSheet(Session session, long employeeId, int year, int month) {
        return session.createSelectionQuery("from SalaryProcessing s where s.employee.id = :employee"
                        + " and s.year = :year and s.month = :month", SalaryProcessing.class)
                .setParameter("employee", employeeId)
                .setParameter("year", year)
                .setParameter("month", month)
                .uniqueResultOptional();
    }

    /** One employee's sheets, newest month first. */
    public List<SalaryProcessing> sheetsOf(Session session, long employeeId) {
        return session.createSelectionQuery("from SalaryProcessing s where s.employee.id = :employee"
                        + " order by s.year desc, s.month desc", SalaryProcessing.class)
                .setParameter("employee", employeeId)
                .getResultList();
    }

    /** Every employee's sheet of one month, with the employee loaded. */
    public List<SalaryProcessing> sheetsOfMonth(Session session, int year, int month) {
        return session.createSelectionQuery("from SalaryProcessing s join fetch s.employee"
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

    /** Payments by id with the employee loaded (names the payment in the account ledger). */
    public Map<Long, SalaryPayment> findPaymentsByIds(Session session, Collection<Long> ids) {
        Map<Long, SalaryPayment> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        session.createSelectionQuery("from SalaryPayment p join fetch p.employee where p.id in :ids", SalaryPayment.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(p -> result.put(p.getId(), p));
        return result;
    }

    /** One employee's payments, newest first, with the account loaded. */
    public List<SalaryPayment> paymentsOf(Session session, long employeeId) {
        return session.createSelectionQuery("from SalaryPayment p join fetch p.account where p.employee.id = :employee"
                        + " order by p.date desc, p.id desc", SalaryPayment.class)
                .setParameter("employee", employeeId)
                .getResultList();
    }

    /** One employee's payments for one month, oldest first, with the account loaded. */
    public List<SalaryPayment> paymentsOf(Session session, long employeeId, int year, int month) {
        return session.createSelectionQuery("from SalaryPayment p join fetch p.account where p.employee.id = :employee"
                        + " and p.year = :year and p.month = :month order by p.date, p.id", SalaryPayment.class)
                .setParameter("employee", employeeId)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList();
    }

    /** One employee's total paid per salary month, leaving out payment {@code excludeId} (may be null). */
    public Map<YearMonth, BigDecimal> paidByMonth(Session session, long employeeId, Long excludeId) {
        var query = session.createSelectionQuery("select p.year, p.month, sum(p.amount) from SalaryPayment p"
                        + " where p.employee.id = :employee" + (excludeId == null ? "" : " and p.id <> :id")
                        + " group by p.year, p.month", Object[].class)
                .setParameter("employee", employeeId);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        Map<YearMonth, BigDecimal> result = new HashMap<>();
        query.getResultList().forEach(r -> result.put(YearMonth.of((Integer) r[0], (Integer) r[1]), (BigDecimal) r[2]));
        return result;
    }

    /** Every employee's net salary per processed month up to and including {@code year}-{@code month}: employee id, month, net. */
    public Map<Long, Map<YearMonth, BigDecimal>> netsUpTo(Session session, int year, int month) {
        return byEmployeeMonth(session.createSelectionQuery("select s.employee.id, s.year, s.month, s.netSalary"
                        + " from SalaryProcessing s where s.year < :year or (s.year = :year and s.month <= :month)",
                        Object[].class)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList());
    }

    /** Every employee's total paid per salary month up to and including {@code year}-{@code month}. */
    public Map<Long, Map<YearMonth, BigDecimal>> paidUpTo(Session session, int year, int month) {
        return byEmployeeMonth(session.createSelectionQuery("select p.employee.id, p.year, p.month, sum(p.amount)"
                        + " from SalaryPayment p where p.year < :year or (p.year = :year and p.month <= :month)"
                        + " group by p.employee.id, p.year, p.month", Object[].class)
                .setParameter("year", year)
                .setParameter("month", month)
                .getResultList());
    }

    private static Map<Long, Map<YearMonth, BigDecimal>> byEmployeeMonth(List<Object[]> rows) {
        Map<Long, Map<YearMonth, BigDecimal>> result = new HashMap<>();
        for (Object[] r : rows) {
            result.computeIfAbsent((Long) r[0], k -> new HashMap<>())
                    .put(YearMonth.of((Integer) r[1], (Integer) r[2]), (BigDecimal) r[3]);
        }
        return result;
    }

    /** Total paid per employee id for one month. */
    public Map<Long, BigDecimal> paidByEmployee(Session session, int year, int month) {
        Map<Long, BigDecimal> result = new HashMap<>();
        session.createSelectionQuery("select p.employee.id, sum(p.amount) from SalaryPayment p"
                        + " where p.year = :year and p.month = :month group by p.employee.id", Object[].class)
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
