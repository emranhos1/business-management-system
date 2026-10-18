package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Employee;
import com.eh.bms.model.EmployeeStatus;
import com.eh.bms.model.SalaryType;
import com.eh.bms.repository.EmployeeRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Employees (14.1). Name, mobile number (11 digits), salary type and base salary are required; NID is unique
 * when given. An employee with attendance, salary sheets or payments is never deleted, only made inactive.
 */
public class EmployeeService {

    public enum SaveResult {
        SAVED, DUPLICATE_NID, NOT_FOUND
    }

    public enum DeleteResult {
        DELETED, IN_USE, NOT_FOUND
    }

    public enum StatusResult {
        DONE,
        /** Inactive refused: salary is due on a processed month ({@code amount}). */
        HAS_DUE,
        /** Inactive refused: advance paid and not earned yet ({@code amount}). */
        HAS_ADVANCE,
        NOT_FOUND
    }

    /** {@code amount}: the open money for HAS_DUE / HAS_ADVANCE, else null. */
    public record StatusOutcome(StatusResult status, BigDecimal amount) {
    }

    /**
     * Values from the form. {@code id} is null for a new employee (saved ACTIVE). {@code baseSalary} is per month,
     * day or hour, by {@code salaryType}. nid, address, designation and joinDate may be null/blank.
     */
    public record EmployeeData(Long id, String name, String phone, String nid, String address, String designation,
                               LocalDate joinDate, SalaryType salaryType, BigDecimal baseSalary) {
    }

    public static final int NAME_MAX = 150;
    public static final int PHONE_LENGTH = 11;
    public static final int NID_MAX = 30;
    public static final int ADDRESS_MAX = 255;
    public static final int DESIGNATION_MAX = 100;

    private final EmployeeRepository repository = new EmployeeRepository();
    private final AuditService auditService = new AuditService();
    private final SalaryService salaryService = new SalaryService();

    /** One page of employees with this status, sorted by name; {@code search} matches name, phone, NID or designation. */
    public PageResult<Employee> page(EmployeeStatus status, String search, int offset, int limit) {
        return TransactionHelper.inTransaction(session -> new PageResult<>(
                repository.page(session, status, search, offset, limit), repository.count(session, status, search)));
    }

    /** Active employees sorted by name (pickers). */
    public List<Employee> active() {
        return TransactionHelper.inTransaction(session -> repository.findByStatus(session, EmployeeStatus.ACTIVE));
    }

    public Optional<Employee> findById(long id) {
        return TransactionHelper.inTransaction(session -> repository.findById(session, id));
    }

    /**
     * Adds ({@code id} null) or changes an employee.
     *
     * @throws IllegalArgumentException when a required value is missing or wrong (the form checks these first)
     */
    public SaveResult save(EmployeeData data) {
        validate(data);
        String nid = blankToNull(data.nid());
        return TransactionHelper.inTransaction(session -> {
            if (nid != null && repository.existsByNid(session, nid, data.id())) {
                return SaveResult.DUPLICATE_NID;
            }
            if (data.id() == null) {
                Employee e = new Employee();
                fill(e, data, nid);
                e.setStatus(EmployeeStatus.ACTIVE);
                repository.save(session, e);
                auditService.created(session, AppModule.EMPLOYEE, e.getId(), describe(e));
                return SaveResult.SAVED;
            }
            Employee e = repository.findById(session, data.id()).orElse(null);
            if (e == null) {
                return SaveResult.NOT_FOUND;
            }
            String old = describe(e);
            fill(e, data, nid);
            String now = describe(e);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, AppModule.EMPLOYEE, e.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /**
     * Makes an employee active or inactive. Inactive ones leave the lists, pickers and attendance sheet. Inactive
     * is refused while salary is due on a processed month or advance is not earned yet.
     */
    public StatusOutcome setStatus(long id, EmployeeStatus status) {
        Objects.requireNonNull(status, "status");
        return TransactionHelper.inTransaction(session -> {
            Employee e = repository.lock(session, id).orElse(null);
            if (e == null) {
                return new StatusOutcome(StatusResult.NOT_FOUND, null);
            }
            if (status == EmployeeStatus.INACTIVE && e.getStatus() != status) {
                SalaryService.OpenMoney open = salaryService.openMoney(session, id);
                if (open.due().signum() > 0) {
                    return new StatusOutcome(StatusResult.HAS_DUE, open.due());
                }
                if (open.advance().signum() > 0) {
                    return new StatusOutcome(StatusResult.HAS_ADVANCE, open.advance());
                }
            }
            if (e.getStatus() != status) {
                String old = describe(e);
                e.setStatus(status);
                auditService.updated(session, AppModule.EMPLOYEE, id, old, describe(e));
            }
            return new StatusOutcome(StatusResult.DONE, null);
        });
    }

    /** True if {@link #delete} would delete the employee: no attendance, salary sheet or salary payment. */
    public boolean canDelete(long id) {
        return TransactionHelper.inTransaction(session ->
                repository.findById(session, id).isPresent() && !repository.isUsed(session, id));
    }

    /** Deletes only an employee with no attendance, salary sheet or payment; otherwise make it inactive. */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Employee e = repository.findById(session, id).orElse(null);
            if (e == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id)) {
                return DeleteResult.IN_USE;
            }
            String old = describe(e);
            repository.delete(session, e);
            auditService.deleted(session, AppModule.EMPLOYEE, id, old);
            return DeleteResult.DELETED;
        });
    }

    private static void validate(EmployeeData data) {
        if (data.name() == null || data.name().isBlank() || data.name().trim().length() > NAME_MAX) {
            throw new IllegalArgumentException("Name is required (at most " + NAME_MAX + " characters)");
        }
        if (data.phone() == null || !data.phone().trim().matches("\\d{" + PHONE_LENGTH + "}")) {
            throw new IllegalArgumentException("Mobile number must be " + PHONE_LENGTH + " digits");
        }
        if (data.salaryType() == null) {
            throw new IllegalArgumentException("Salary type is required");
        }
        if (data.baseSalary() == null || data.baseSalary().signum() <= 0) {
            throw new IllegalArgumentException("Base salary must be more than 0");
        }
        String nid = blankToNull(data.nid());
        if (nid != null && nid.length() > NID_MAX) {
            throw new IllegalArgumentException("NID is too long");
        }
    }

    private static void fill(Employee e, EmployeeData data, String nid) {
        e.setName(data.name().trim());
        e.setPhone(data.phone().trim());
        e.setNid(nid);
        e.setAddress(cut(blankToNull(data.address()), ADDRESS_MAX));
        e.setDesignation(cut(blankToNull(data.designation()), DESIGNATION_MAX));
        e.setJoinDate(data.joinDate());
        e.setSalaryType(data.salaryType());
        e.setBaseSalary(data.baseSalary());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String describe(Employee e) {
        return new StringJoiner(", ")
                .add("name=" + e.getName())
                .add("phone=" + e.getPhone())
                .add("nid=" + e.getNid())
                .add("address=" + e.getAddress())
                .add("designation=" + e.getDesignation())
                .add("joinDate=" + e.getJoinDate())
                .add("salaryType=" + e.getSalaryType())
                .add("baseSalary=" + (e.getBaseSalary() == null ? null : e.getBaseSalary().toPlainString()))
                .add("status=" + e.getStatus())
                .toString();
    }
}
