package com.eh.bms.service;

import com.eh.bms.model.Attendance;
import com.eh.bms.model.AttendanceStatus;
import com.eh.bms.model.Employee;
import com.eh.bms.model.EmployeeStatus;
import com.eh.bms.model.SalaryType;
import com.eh.bms.repository.AttendanceRepository;
import com.eh.bms.repository.EmployeeRepository;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Daily attendance (14.2), optional: salary works without it. One row per employee per day; a whole day is
 * saved at once. In/out times are optional (out must be after in) and kept only for present / half-day rows.
 * Present is saved automatically every work day ({@link #autoFill}); the admin changes only the exceptions.
 */
public class AttendanceService {

    public enum Status {
        SAVED, FUTURE_DATE, BAD_TIME, NOT_FOUND
    }

    /** {@code employeeId} names the row that failed (BAD_TIME, NOT_FOUND). */
    public record Result(Status status, Long employeeId) {
    }

    /** One employee's row on the day sheet. {@code status} null = no attendance for that day (an existing row is removed). */
    public record Entry(long employeeId, AttendanceStatus status, LocalTime inTime, LocalTime outTime, String note) {
    }

    /** One line of the day sheet: an employee and their row that day, or null if none. */
    public record DayRow(Employee employee, Attendance attendance) {
    }

    /**
     * One employee's attendance in a month. {@code hours} counts present / half-day rows that have both times;
     * {@code daysWithoutTime} is how many present / half-day rows were left out of the hours for a missing time.
     */
    public record MonthSummary(Employee employee, int present, int halfDay, int leave, int absent, BigDecimal hours,
                               int daysWithoutTime) {
        /** Days worked: present + half days x 0.5. */
        public BigDecimal workedDays() {
            return BigDecimal.valueOf(present).add(BigDecimal.valueOf(halfDay).multiply(HALF)).setScale(2);
        }

        /** Days the employee came (present + half days). */
        public int daysCame() {
            return present + halfDay;
        }

        /** True when the month has any attendance row. */
        public boolean hasRows() {
            return present + halfDay + leave + absent > 0;
        }
    }

    static final BigDecimal HALF = new BigDecimal("0.5");
    private static final int NOTE_MAX = 255;
    private static final String RECORD = "ATTENDANCE";

    private final AttendanceRepository repository = new AttendanceRepository();
    private final EmployeeRepository employeeRepository = new EmployeeRepository();
    private final AuditService auditService = new AuditService();
    private final AppConfigService appConfig = new AppConfigService();

    /**
     * The day sheet: every active employee with their row (or null), plus inactive employees that have a row that
     * day, sorted by name.
     */
    public List<DayRow> loadDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return TransactionHelper.inTransaction(session -> {
            Map<Long, Attendance> rows = new HashMap<>();
            repository.findByDate(session, date).forEach(a -> rows.put(a.getEmployee().getId(), a));
            Map<Long, Employee> employees = new LinkedHashMap<>();
            employeeRepository.findByStatus(session, EmployeeStatus.ACTIVE).forEach(e -> employees.put(e.getId(), e));
            rows.values().forEach(a -> employees.putIfAbsent(a.getEmployee().getId(), a.getEmployee()));
            List<DayRow> result = new ArrayList<>();
            employees.values().stream()
                    .sorted(Comparator.comparing(Employee::getName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(Employee::getId))
                    .forEach(e -> result.add(new DayRow(e, rows.get(e.getId()))));
            return result;
        });
    }

    /**
     * Saves a whole day: each entry adds, changes or (status null) removes that employee's row. Nothing is saved
     * if any entry is wrong. Employees not in {@code entries} are left as they are.
     */
    public Result saveDay(LocalDate date, Collection<Entry> entries) {
        Objects.requireNonNull(date, "date");
        if (date.isAfter(LocalDate.now())) {
            return new Result(Status.FUTURE_DATE, null);
        }
        for (Entry e : entries) {
            if (e.status() != null && keepsTime(e.status()) && e.inTime() != null && e.outTime() != null
                    && !e.outTime().isAfter(e.inTime())) {
                return new Result(Status.BAD_TIME, e.employeeId());
            }
        }
        try {
            return TransactionHelper.inTransaction(session -> saveAll(session, date, entries));
        } catch (Abort a) {
            return a.result;
        }
    }

    private Result saveAll(Session session, LocalDate date, Collection<Entry> entries) {
        Set<Long> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.employeeId())) {
                continue; // the same employee twice: the first entry wins
            }
            Employee employee = employeeRepository.findById(session, entry.employeeId()).orElse(null);
            if (employee == null) {
                throw new Abort(new Result(Status.NOT_FOUND, entry.employeeId()));
            }
            saveOne(session, employee, date, entry);
        }
        return new Result(Status.SAVED, null);
    }

    private void saveOne(Session session, Employee employee, LocalDate date, Entry entry) {
        Attendance a = repository.find(session, employee.getId(), date).orElse(null);
        if (entry.status() == null) {
            if (a != null) {
                String old = describe(a);
                repository.delete(session, a);
                auditService.deleted(session, RECORD, a.getId(), old);
            }
            return;
        }
        boolean added = a == null;
        String old = added ? null : describe(a);
        if (added) {
            a = new Attendance();
            a.setEmployee(employee);
            a.setDate(date);
        }
        a.setStatus(entry.status());
        boolean times = keepsTime(entry.status());
        a.setInTime(times ? entry.inTime() : null);
        a.setOutTime(times ? entry.outTime() : null);
        String note = entry.note() == null ? "" : entry.note().trim();
        a.setNote(note.isEmpty() ? null : note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note);
        if (added) {
            repository.save(session, a);
            auditService.created(session, RECORD, a.getId(), describe(a));
        } else {
            String now = describe(a);
            if (!old.equals(now)) {
                auditService.updated(session, RECORD, a.getId(), old, now);
            }
        }
    }

    /**
     * Auto attendance: saves PRESENT for every active employee on each day from the later of their join date and
     * {@code attendance_auto_from} up to {@code upTo} (never after today), skipping weekly off days and days that
     * already have a row. Hourly employees get the work day start / end as in / out time. One audit row per run
     * that added anything. Returns how many rows were added.
     */
    public int autoFill(LocalDate upTo) {
        Objects.requireNonNull(upTo, "upTo");
        LocalDate today = LocalDate.now();
        LocalDate end = upTo.isAfter(today) ? today : upTo;
        String fromText = appConfig.getString(AppConfigService.ATTENDANCE_AUTO_FROM, null);
        LocalDate from;
        try {
            from = fromText == null || fromText.isBlank() ? null : LocalDate.parse(fromText.trim());
        } catch (DateTimeParseException e) {
            from = null;
        }
        if (from == null) {
            // Not set yet (DataSeeder sets it): start today, never fill older days
            appConfig.set(AppConfigService.ATTENDANCE_AUTO_FROM, today.toString());
            from = today;
        }
        if (end.isBefore(from)) {
            return 0;
        }
        Set<DayOfWeek> offDays = appConfig.weeklyOffDays();
        LocalTime start = appConfig.workStart();
        LocalTime finish = appConfig.workEnd();
        if (!finish.isAfter(start)) {
            start = AppConfigService.WORK_START_DEFAULT;
            finish = AppConfigService.WORK_END_DEFAULT;
        }
        LocalDate first = from;
        LocalTime in = start;
        LocalTime out = finish;
        return TransactionHelper.inTransaction(session -> {
            Set<String> existing = repository.keysBetween(session, first, end);
            int added = 0;
            for (Employee e : employeeRepository.findByStatus(session, EmployeeStatus.ACTIVE)) {
                LocalDate day = e.getJoinDate() != null && e.getJoinDate().isAfter(first) ? e.getJoinDate() : first;
                boolean hourly = e.getSalaryType() == SalaryType.HOURLY;
                for (; !day.isAfter(end); day = day.plusDays(1)) {
                    if (offDays.contains(day.getDayOfWeek()) || existing.contains(e.getId() + ":" + day)) {
                        continue;
                    }
                    Attendance a = new Attendance();
                    a.setEmployee(e);
                    a.setDate(day);
                    a.setStatus(AttendanceStatus.PRESENT);
                    a.setInTime(hourly ? in : null);
                    a.setOutTime(hourly ? out : null);
                    repository.save(session, a);
                    added++;
                }
            }
            if (added > 0) {
                auditService.created(session, RECORD, null,
                        "auto attendance PRESENT rows=" + added + " from=" + first + " to=" + end);
            }
            return added;
        });
    }

    /** Every employee with attendance in the month, sorted by name. */
    public List<MonthSummary> monthSummary(YearMonth month) {
        return TransactionHelper.inTransaction(session -> {
            Map<Long, List<Attendance>> byEmployee = new LinkedHashMap<>();
            Map<Long, Employee> employees = new HashMap<>();
            for (Attendance a : repository.findBetween(session, null, month.atDay(1), month.atEndOfMonth())) {
                byEmployee.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
                employees.put(a.getEmployee().getId(), a.getEmployee());
            }
            List<MonthSummary> result = new ArrayList<>();
            byEmployee.forEach((id, rows) -> result.add(summarize(employees.get(id), rows)));
            result.sort(Comparator.comparing((MonthSummary s) -> s.employee().getName(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(s -> s.employee().getId()));
            return result;
        });
    }

    /** One employee's month (all zero when there is no attendance). */
    public MonthSummary monthSummary(long employeeId, YearMonth month) {
        return TransactionHelper.inTransaction(session -> monthSummary(session, employeeId, month));
    }

    MonthSummary monthSummary(Session session, long employeeId, YearMonth month) {
        Employee employee = employeeRepository.findById(session, employeeId)
                .orElseThrow(() -> new IllegalStateException("Employee not found: " + employeeId));
        return summarize(employee, repository.findBetween(session, employeeId, month.atDay(1), month.atEndOfMonth()));
    }

    static MonthSummary summarize(Employee employee, List<Attendance> rows) {
        int present = 0;
        int half = 0;
        int leave = 0;
        int absent = 0;
        int withoutTime = 0;
        long minutes = 0;
        for (Attendance a : rows) {
            switch (a.getStatus()) {
                case PRESENT -> present++;
                case HALF_DAY -> half++;
                case LEAVE -> leave++;
                case ABSENT -> absent++;
            }
            if (keepsTime(a.getStatus())) {
                if (a.getInTime() != null && a.getOutTime() != null && a.getOutTime().isAfter(a.getInTime())) {
                    minutes += Duration.between(a.getInTime(), a.getOutTime()).toMinutes();
                } else {
                    withoutTime++;
                }
            }
        }
        BigDecimal hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        return new MonthSummary(employee, present, half, leave, absent, hours, withoutTime);
    }

    /** In/out times belong to days the employee came. */
    private static boolean keepsTime(AttendanceStatus status) {
        return status == AttendanceStatus.PRESENT || status == AttendanceStatus.HALF_DAY;
    }

    private static String describe(Attendance a) {
        return "attendance employee=" + a.getEmployee().getId() + " date=" + a.getDate() + " status=" + a.getStatus()
                + (a.getInTime() == null ? "" : " in=" + a.getInTime())
                + (a.getOutTime() == null ? "" : " out=" + a.getOutTime())
                + (a.getNote() == null ? "" : " note=" + a.getNote());
    }

    /** Stops a transaction (rolls it back) with a result for the screen. */
    private static final class Abort extends RuntimeException {
        private final Result result;

        Abort(Result result) {
            super(result.status().name(), null, false, false);
            this.result = result;
        }
    }
}
