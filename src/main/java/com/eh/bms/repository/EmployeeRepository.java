package com.eh.bms.repository;

import com.eh.bms.model.Employee;
import com.eh.bms.model.EmployeeStatus;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;

import java.util.List;
import java.util.Optional;

/** Database access for {@code employees}. */
public class EmployeeRepository {

    /**
     * One page of employees with this status, sorted by name; {@code search} (may be blank) matches part of the
     * name, phone, NID or designation.
     */
    public List<Employee> page(Session session, EmployeeStatus status, String search, int offset, int limit) {
        boolean filter = search != null && !search.isBlank();
        var query = session.createSelectionQuery("from Employee e" + where(filter) + " order by e.name, e.id",
                        Employee.class)
                .setParameter("status", status);
        if (filter) {
            query.setParameter("search", like(search));
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public long count(Session session, EmployeeStatus status, String search) {
        boolean filter = search != null && !search.isBlank();
        var query = session.createSelectionQuery("select count(e) from Employee e" + where(filter), Long.class)
                .setParameter("status", status);
        if (filter) {
            query.setParameter("search", like(search));
        }
        return query.getSingleResult();
    }

    private static String where(boolean search) {
        return " where e.status = :status" + (search
                ? " and (e.name like :search or e.phone like :search or e.nid like :search or e.designation like :search)"
                : "");
    }

    /** All employees with this status, sorted by name (pickers, attendance sheet). */
    public List<Employee> findByStatus(Session session, EmployeeStatus status) {
        return session.createSelectionQuery("from Employee e where e.status = :status order by e.name, e.id",
                        Employee.class)
                .setParameter("status", status)
                .getResultList();
    }

    public List<Employee> findByIds(Session session, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return session.createSelectionQuery("from Employee e where e.id in :ids order by e.name, e.id", Employee.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    public Optional<Employee> findById(Session session, long id) {
        return Optional.ofNullable(session.find(Employee.class, id));
    }

    /** Locks the employee row until the transaction ends (salary sheet and payments of one employee). */
    public Optional<Employee> lock(Session session, long id) {
        return Optional.ofNullable(session.find(Employee.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    /** True if another employee (not {@code excludeId}) has this NID. */
    public boolean existsByNid(Session session, String nid, Long excludeId) {
        String hql = "select count(e) from Employee e where e.nid = :nid" + (excludeId == null ? "" : " and e.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("nid", nid);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }

    /** True if any attendance, salary sheet or salary payment uses the employee. */
    public boolean isUsed(Session session, long id) {
        return count(session, "select count(a) from Attendance a where a.employee.id = :id", id) > 0
                || count(session, "select count(s) from SalaryProcessing s where s.employee.id = :id", id) > 0
                || count(session, "select count(p) from SalaryPayment p where p.employee.id = :id", id) > 0;
    }

    public void save(Session session, Employee employee) {
        session.persist(employee);
    }

    public void delete(Session session, Employee employee) {
        session.remove(employee);
    }

    /** {@code %text%} with the LIKE wildcards in the user's text escaped. */
    private static String like(String text) {
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static long count(Session session, String hql, long id) {
        return session.createSelectionQuery(hql, Long.class).setParameter("id", id).getSingleResult();
    }
}
