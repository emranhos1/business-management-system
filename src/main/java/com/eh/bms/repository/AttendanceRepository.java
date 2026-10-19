package com.eh.bms.repository;

import com.eh.bms.model.Attendance;
import org.hibernate.Session;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Database access for {@code attendance}. */
public class AttendanceRepository {

    /** Every row of one day, with the employee loaded. */
    public List<Attendance> findByDate(Session session, LocalDate date) {
        return session.createSelectionQuery("from Attendance a join fetch a.employee where a.date = :date",
                        Attendance.class)
                .setParameter("date", date)
                .getResultList();
    }

    public Optional<Attendance> find(Session session, long employeeId, LocalDate date) {
        return session.createSelectionQuery("from Attendance a where a.employee.id = :employee and a.date = :date",
                        Attendance.class)
                .setParameter("employee", employeeId)
                .setParameter("date", date)
                .uniqueResultOptional();
    }

    /** Rows of the dates {@code from}..{@code to}, all employees ({@code employeeId} null) or one. */
    public List<Attendance> findBetween(Session session, Long employeeId, LocalDate from, LocalDate to) {
        var query = session.createSelectionQuery("from Attendance a join fetch a.employee"
                        + " where a.date >= :from and a.date <= :to"
                        + (employeeId == null ? "" : " and a.employee.id = :employee")
                        + " order by a.date", Attendance.class)
                .setParameter("from", from)
                .setParameter("to", to);
        if (employeeId != null) {
            query.setParameter("employee", employeeId);
        }
        return query.getResultList();
    }

    /** "employeeId:date" of every row from {@code from} to {@code to} (what auto attendance must leave alone). */
    public Set<String> keysBetween(Session session, LocalDate from, LocalDate to) {
        Set<String> keys = new HashSet<>();
        session.createSelectionQuery("select a.employee.id, a.date from Attendance a"
                        + " where a.date >= :from and a.date <= :to", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .forEach(r -> keys.add(r[0] + ":" + r[1]));
        return keys;
    }

    public void save(Session session, Attendance attendance) {
        session.persist(attendance);
    }

    public void delete(Session session, Attendance attendance) {
        session.remove(attendance);
    }
}
