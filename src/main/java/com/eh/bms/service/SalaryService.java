package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.Employee;
import com.eh.bms.model.EmployeeStatus;
import com.eh.bms.model.SalaryPayment;
import com.eh.bms.model.SalaryProcessing;
import com.eh.bms.model.SalaryType;
import com.eh.bms.model.User;
import com.eh.bms.repository.AccountTransactionRepository;
import com.eh.bms.repository.EmployeeRepository;
import com.eh.bms.repository.SalaryRepository;
import com.eh.bms.service.AttendanceService.MonthSummary;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Monthly salary (14.3), payments (14.4) and history (14.5).
 * <ul>
 * <li>A month's sheet ({@code salary_processing}): gross by salary type (monthly = base salary, never cut for
 * absence; daily = days worked x rate; hourly = hours x rate), net = gross - deductions + bonus, never below 0.
 * Days or hours come from attendance when the month has any, else they are typed in. Processing a month again
 * changes its sheet.</li>
 * <li>Payments ({@code salary_payments}): each one is an account DEBIT with source SALARY. Paid before the
 * month's sheet exists = advance; after = part payment. A payment never goes above the month's due, except an
 * emergency advance; money out never goes above what the account holds on that date.</li>
 * <li>Carry forward: whatever a processed month got above its net (an emergency advance, or a net lowered after
 * paying) counts as paid for the next processed months, oldest first ({@link #chain}). A due is never below 0;
 * the rest is "advance left".</li>
 * </ul>
 * Lock order: employee row, then account rows (by id).
 */
public class SalaryService {

    public enum Status {
        SAVED,
        NOT_FOUND,
        /** The sheet is for a month after the current one. */
        FUTURE_MONTH,
        /** Deductions are more than gross + bonus. */
        NEGATIVE_NET,
        /** Payment (not an emergency advance): more than the month's due; {@code amount} = most still payable. */
        OVER_NET,
        /** Payment: the account does not hold that much on that date; {@code amount} = most it can pay. */
        INSUFFICIENT_BALANCE
    }

    public record Result(Status status, BigDecimal amount) {
        static Result of(Status status) {
            return new Result(status, null);
        }
    }

    /**
     * Values from the sheet form. {@code workedUnits}: days (daily) or hours (hourly), required for those, optional
     * (days, for the record) for monthly. {@code deductions} / {@code bonus} null = 0.
     */
    public record ProcessData(long employeeId, YearMonth month, int presentDays, int leaveDays, BigDecimal workedUnits,
                              BigDecimal deductions, BigDecimal bonus, String note) {
    }

    /**
     * What the sheet form opens with. From the saved sheet when the month is processed ({@code sheet} set), else
     * prefilled from attendance (when {@code attendance.hasRows()}), else empty units to type in.
     * {@code paid}: paid for the month so far (advances when not processed); {@code carryIn}: advance carried
     * from earlier months.
     */
    public record Draft(Employee employee, YearMonth month, int workingDays, MonthSummary attendance,
                        SalaryProcessing sheet, int presentDays, int leaveDays, BigDecimal workedUnits,
                        BigDecimal gross, BigDecimal deductions, BigDecimal bonus, BigDecimal net, String note,
                        BigDecimal paid, BigDecimal carryIn) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /**
     * Values from the payment form. {@code id} null = new payment. {@code month}: the salary month it is for.
     * {@code emergency}: an emergency advance, allowed above the month's due.
     */
    public record PaymentData(Long id, long employeeId, YearMonth month, LocalDate date, BigDecimal amount,
                              long accountId, String note, boolean emergency) {
    }

    /**
     * A month's money for one employee. {@code carryIn}: advance carried from earlier months. When processed,
     * net - carryIn - paid = due - advanceLeft (both never below 0). {@code net}, {@code due} and
     * {@code advanceLeft} are null when the month is not processed (then {@code paid} is all advance).
     */
    public record MonthDue(YearMonth month, SalaryProcessing sheet, BigDecimal net, BigDecimal paid,
                           BigDecimal carryIn, BigDecimal due, BigDecimal advanceLeft) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /** One month in an employee's history; sheet values (and due, advanceLeft) are null when only advances were paid. */
    public record MonthRow(YearMonth month, SalaryProcessing sheet, BigDecimal gross, BigDecimal deductions,
                           BigDecimal bonus, BigDecimal net, BigDecimal carryIn, BigDecimal paid, BigDecimal due,
                           BigDecimal advanceLeft) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /**
     * An employee's salary history, newest month first, and all payments, newest first. {@code totalDue} sums the
     * processed months' dues; {@code totalAdvance} is advance not earned yet (carried past the last processed month,
     * plus paid for months not processed). totalNet - totalPaid = totalDue - totalAdvance.
     */
    public record History(Employee employee, List<MonthRow> months, List<SalaryPayment> payments, BigDecimal totalNet,
                          BigDecimal totalPaid, BigDecimal totalDue, BigDecimal totalAdvance) {
    }

    /** One employee on the month sheet; {@code sheet} null = not processed (then due and advanceLeft are null). */
    public record SheetRow(Employee employee, SalaryProcessing sheet, BigDecimal carryIn, BigDecimal paid,
                           BigDecimal due, BigDecimal advanceLeft) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /**
     * All employees' salary for one month (16.10): active employees plus anyone with a sheet or payment that
     * month, sorted by name. Totals sum the processed rows, except {@code totalPaid} (every payment) and
     * {@code totalCarry} (advance carried in, every row).
     */
    public record MonthSheet(YearMonth month, int workingDays, List<SheetRow> rows, BigDecimal totalGross,
                             BigDecimal totalDeductions, BigDecimal totalBonus, BigDecimal totalNet,
                             BigDecimal totalCarry, BigDecimal totalPaid, BigDecimal totalDue) {
    }

    /** Money still open with an employee: due on processed months, and advance not earned yet. */
    public record OpenMoney(BigDecimal due, BigDecimal advance) {
    }

    /**
     * One month of the carry chain. {@code net} null = not processed: the carry passes it unchanged and its
     * payments wait for it (then due and advanceLeft are null).
     */
    record Step(YearMonth month, BigDecimal net, BigDecimal paid, BigDecimal carryIn, BigDecimal due,
                BigDecimal advanceLeft) {
    }

    /**
     * The chain, oldest month first; {@code carry} = left after the last processed month; {@code pending} = paid
     * for months not processed.
     */
    record Chain(Map<YearMonth, Step> steps, BigDecimal carry, BigDecimal pending) {
        BigDecimal totalDue() {
            return steps.values().stream().map(Step::due).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Advance not earned yet. */
        BigDecimal advance() {
            return carry.add(pending);
        }
    }

    private static final int NOTE_MAX = 255;
    /** worked_units is DECIMAL(8,2). */
    private static final BigDecimal UNITS_MAX = new BigDecimal("999999.99");
    private static final String SHEET = "SALARY_SHEET";
    private static final String PAYMENT = "SALARY_PAYMENT";

    private final SalaryRepository repository = new SalaryRepository();
    private final EmployeeRepository employeeRepository = new EmployeeRepository();
    private final AccountTransactionRepository accountTransactions = new AccountTransactionRepository();
    private final AccountLedgerService ledger = new AccountLedgerService();
    private final AttendanceService attendanceService = new AttendanceService();
    private final AppConfigService appConfig = new AppConfigService();
    private final AuditService auditService = new AuditService();

    // ---------------------------------------------------------------- working days and gross

    /** Days in the month minus the weekly off days from Settings. */
    public int workingDays(YearMonth month) {
        return workingDays(month, appConfig.weeklyOffDays());
    }

    /** Days in the month minus the days falling on {@code offDays} (empty = every day is a working day). */
    public static int workingDays(YearMonth month, Set<DayOfWeek> offDays) {
        int days = 0;
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            if (!offDays.contains(month.atDay(d).getDayOfWeek())) {
                days++;
            }
        }
        return days;
    }

    /** Gross by salary type: monthly = base; daily / hourly = units x base (null units = 0). */
    public static BigDecimal gross(SalaryType type, BigDecimal base, BigDecimal workedUnits) {
        if (type == SalaryType.MONTHLY) {
            return base.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal units = workedUnits == null ? BigDecimal.ZERO : workedUnits;
        return units.multiply(base).setScale(2, RoundingMode.HALF_UP);
    }

    /** gross - deductions + bonus (nulls = 0); may be negative (the form then shows an error). */
    public static BigDecimal net(BigDecimal gross, BigDecimal deductions, BigDecimal bonus) {
        return zero(gross).subtract(zero(deductions)).add(zero(bonus)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Days (daily, monthly) or hours (hourly) worked from the month's attendance. With paid leave (Settings) a leave
     * day counts as a worked day for daily and as {@code dayHours} for hourly; monthly is never affected.
     */
    static BigDecimal workedUnits(SalaryType type, MonthSummary att, boolean paidLeave, BigDecimal dayHours) {
        BigDecimal units = type == SalaryType.HOURLY ? att.hours() : att.workedDays();
        if (paidLeave && att.leave() > 0 && type != SalaryType.MONTHLY) {
            BigDecimal leave = BigDecimal.valueOf(att.leave());
            units = units.add(type == SalaryType.HOURLY ? dayHours.multiply(leave) : leave);
        }
        return units.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- month sheet (14.3)

    /** What the sheet form opens with for this employee and month. */
    public Draft draft(long employeeId, YearMonth month) {
        Set<DayOfWeek> offDays = appConfig.weeklyOffDays();
        boolean paidLeave = appConfig.paidLeave();
        BigDecimal dayHours = appConfig.workDayHours();
        return TransactionHelper.inTransaction(session -> {
            Employee e = employeeRepository.findById(session, employeeId)
                    .orElseThrow(() -> new IllegalStateException("Employee not found: " + employeeId));
            int working = workingDays(month, offDays);
            MonthSummary att = attendanceService.monthSummary(session, employeeId, month);
            MonthDue due = monthDue(session, employeeId, month, null);
            BigDecimal paid = due.paid();
            SalaryProcessing s = due.sheet();
            if (s != null) {
                return new Draft(e, month, working, att, s, s.getPresentDays(), s.getLeaveDays(), s.getWorkedUnits(),
                        s.getGrossSalary(), s.getDeductions(), s.getBonus(), s.getNetSalary(), s.getNote(), paid,
                        due.carryIn());
            }
            BigDecimal units = null;
            if (att.hasRows()) {
                units = workedUnits(e.getSalaryType(), att, paidLeave, dayHours);
            }
            BigDecimal gross = e.getSalaryType() == SalaryType.MONTHLY || units != null
                    ? gross(e.getSalaryType(), e.getBaseSalary(), units) : null;
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new Draft(e, month, working, att, null, att.daysCame(), att.leave(), units, gross, zero, zero,
                    gross == null ? null : net(gross, zero, zero), null, paid, due.carryIn());
        });
    }

    /**
     * Works out (or works out again) the employee's sheet for the month with the employee's current salary type and
     * base salary. Refused when the net would be below 0; paid above the net carries to the next months.
     *
     * @throws IllegalArgumentException when a value is missing or out of range (the form checks these first)
     */
    public Result process(ProcessData data) {
        Objects.requireNonNull(data.month(), "month");
        if (data.month().isAfter(YearMonth.now())) {
            return Result.of(Status.FUTURE_MONTH);
        }
        int days = data.month().lengthOfMonth();
        if (data.presentDays() < 0 || data.leaveDays() < 0 || data.presentDays() + data.leaveDays() > days) {
            throw new IllegalArgumentException("Present and leave days must be 0.." + days);
        }
        if (zero(data.deductions()).signum() < 0 || zero(data.bonus()).signum() < 0) {
            throw new IllegalArgumentException("Deductions and bonus cannot be below 0");
        }
        if (data.workedUnits() != null
                && (data.workedUnits().signum() < 0 || data.workedUnits().compareTo(UNITS_MAX) > 0)) {
            throw new IllegalArgumentException("Worked days / hours out of range");
        }
        int working = workingDays(data.month());
        return TransactionHelper.inTransaction(session -> {
            Employee e = employeeRepository.lock(session, data.employeeId()).orElse(null);
            if (e == null) {
                return Result.of(Status.NOT_FOUND);
            }
            if (e.getSalaryType() != SalaryType.MONTHLY) {
                if (data.workedUnits() == null) {
                    throw new IllegalArgumentException("Worked days / hours are required");
                }
                if (e.getSalaryType() == SalaryType.DAILY && data.workedUnits().compareTo(BigDecimal.valueOf(days)) > 0) {
                    throw new IllegalArgumentException("Worked days cannot be more than " + days);
                }
            }
            BigDecimal gross = gross(e.getSalaryType(), e.getBaseSalary(), data.workedUnits());
            BigDecimal deductions = zero(data.deductions()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal bonus = zero(data.bonus()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = net(gross, deductions, bonus);
            if (net.signum() < 0) {
                return Result.of(Status.NEGATIVE_NET);
            }
            int y = data.month().getYear();
            int m = data.month().getMonthValue();
            SalaryProcessing s = repository.findSheet(session, e.getId(), y, m).orElse(null);
            boolean added = s == null;
            String old = added ? null : describe(s);
            if (added) {
                s = new SalaryProcessing();
                s.setEmployee(e);
                s.setYear(y);
                s.setMonth(m);
            }
            s.setWorkingDays(working);
            s.setPresentDays(data.presentDays());
            s.setLeaveDays(data.leaveDays());
            s.setWorkedUnits(data.workedUnits() == null ? null : data.workedUnits().setScale(2, RoundingMode.HALF_UP));
            s.setGrossSalary(gross);
            s.setDeductions(deductions);
            s.setBonus(bonus);
            s.setNetSalary(net);
            s.setNote(note(data.note()));
            if (added) {
                repository.saveSheet(session, s);
                auditService.created(session, SHEET, s.getId(), describe(s));
            } else {
                String now = describe(s);
                if (!old.equals(now)) {
                    auditService.updated(session, SHEET, s.getId(), old, now);
                }
            }
            return Result.of(Status.SAVED);
        });
    }

    /** Removes a month's sheet; payments already made for it count as advances again. */
    public Status deleteSheet(long employeeId, YearMonth month) {
        return TransactionHelper.inTransaction(session -> {
            if (employeeRepository.lock(session, employeeId).isEmpty()) {
                return Status.NOT_FOUND;
            }
            SalaryProcessing s = repository.findSheet(session, employeeId, month.getYear(), month.getMonthValue())
                    .orElse(null);
            if (s == null) {
                return Status.NOT_FOUND;
            }
            String old = describe(s);
            repository.deleteSheet(session, s);
            auditService.deleted(session, SHEET, s.getId(), old);
            return Status.SAVED;
        });
    }

    // ---------------------------------------------------------------- payments (14.4)

    /**
     * The month's net, advance carried in, paid and due for the payment form, leaving out payment
     * {@code excludePaymentId} (the one being edited; may be null).
     */
    public MonthDue monthDue(long employeeId, YearMonth month, Long excludePaymentId) {
        return TransactionHelper.inTransaction(session -> monthDue(session, employeeId, month, excludePaymentId));
    }

    private MonthDue monthDue(Session session, long employeeId, YearMonth month, Long excludePaymentId) {
        Map<YearMonth, SalaryProcessing> sheets = sheets(session, employeeId);
        Step st = chain(nets(sheets), repository.paidByMonth(session, employeeId, excludePaymentId), month)
                .steps().get(month);
        return new MonthDue(month, sheets.get(month), st.net(), st.paid(), st.carryIn(), st.due(), st.advanceLeft());
    }

    /** Due on processed months and advance not earned yet (an employee with either cannot be made inactive). */
    OpenMoney openMoney(Session session, long employeeId) {
        Chain c = chain(nets(sheets(session, employeeId)), repository.paidByMonth(session, employeeId, null), null);
        return new OpenMoney(c.totalDue(), c.advance());
    }

    /**
     * The carry chain over every month with a net or a payment (and {@code extra}, may be null), oldest first. A
     * processed month takes the carry plus its own payments: what is short is its due, what is above its net
     * carries on to the next processed month. A month not processed passes the carry on; its payments wait for it.
     */
    static Chain chain(Map<YearMonth, BigDecimal> nets, Map<YearMonth, BigDecimal> paid, YearMonth extra) {
        TreeSet<YearMonth> months = new TreeSet<>(nets.keySet());
        months.addAll(paid.keySet());
        if (extra != null) {
            months.add(extra);
        }
        Map<YearMonth, Step> steps = new LinkedHashMap<>();
        BigDecimal carry = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        for (YearMonth m : months) {
            BigDecimal p = paid.getOrDefault(m, BigDecimal.ZERO);
            BigDecimal net = nets.get(m);
            if (net == null) {
                steps.put(m, new Step(m, null, p, carry, null, null));
                pending = pending.add(p);
                continue;
            }
            BigDecimal left = p.add(carry).subtract(net);
            BigDecimal due = left.signum() < 0 ? left.negate() : BigDecimal.ZERO;
            BigDecimal advanceLeft = left.max(BigDecimal.ZERO);
            steps.put(m, new Step(m, net, p, carry, due, advanceLeft));
            carry = advanceLeft;
        }
        return new Chain(steps, carry, pending);
    }

    private Map<YearMonth, SalaryProcessing> sheets(Session session, long employeeId) {
        Map<YearMonth, SalaryProcessing> sheets = new HashMap<>();
        repository.sheetsOf(session, employeeId).forEach(s -> sheets.put(YearMonth.of(s.getYear(), s.getMonth()), s));
        return sheets;
    }

    private static Map<YearMonth, BigDecimal> nets(Map<YearMonth, SalaryProcessing> sheets) {
        Map<YearMonth, BigDecimal> nets = new HashMap<>();
        sheets.forEach((m, s) -> nets.put(m, s.getNetSalary()));
        return nets;
    }

    /** Adds ({@code id} null) or changes a salary payment, with its money out of the account. */
    public Result savePayment(PaymentData data) {
        if (data.amount() == null || data.amount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be more than 0");
        }
        Objects.requireNonNull(data.date(), "date");
        Objects.requireNonNull(data.month(), "month");
        try {
            return TransactionHelper.inTransaction(session -> data.id() == null
                    ? addPayment(session, data) : changePayment(session, data));
        } catch (Abort a) {
            return a.result;
        }
    }

    private Result addPayment(Session session, PaymentData data) {
        Employee e = employeeRepository.lock(session, data.employeeId()).orElse(null);
        if (e == null) {
            return Result.of(Status.NOT_FOUND);
        }
        MonthDue due = monthDue(session, e.getId(), data.month(), null);
        if (!data.emergency() && due.processed() && due.due().compareTo(data.amount()) < 0) {
            return new Result(Status.OVER_NET, due.due());
        }
        Account account = ledger.lock(session, data.accountId());
        BigDecimal available = ledger.availableOn(session, account.getId(), data.date());
        if (available.compareTo(data.amount()) < 0) {
            return new Result(Status.INSUFFICIENT_BALANCE, available.max(BigDecimal.ZERO));
        }
        SalaryPayment p = new SalaryPayment();
        p.setEmployee(e);
        fill(p, account, data);
        if (UserSession.isLoggedIn()) {
            p.setCreatedBy(session.getReference(User.class, UserSession.get().getUserId()));
        }
        repository.savePayment(session, p);
        post(session, p);
        auditService.created(session, PAYMENT, p.getId(), describe(p));
        return Result.of(Status.SAVED);
    }

    private Result changePayment(Session session, PaymentData data) {
        Employee e = employeeRepository.lock(session, data.employeeId()).orElse(null);
        SalaryPayment p = repository.findPayment(session, data.id()).orElse(null);
        if (e == null || p == null) {
            return Result.of(Status.NOT_FOUND);
        }
        if (!p.getEmployee().getId().equals(e.getId())) {
            throw new IllegalArgumentException("A payment cannot move to another employee");
        }
        MonthDue due = monthDue(session, e.getId(), data.month(), p.getId());
        if (!data.emergency() && due.processed() && due.due().compareTo(data.amount()) < 0) {
            return new Result(Status.OVER_NET, due.due());
        }
        String old = describe(p);
        // Both accounts in id order (the same order as transfers), so two saves cannot deadlock
        long oldAccountId = p.getAccount().getId();
        Account first = ledger.lock(session, Math.min(oldAccountId, data.accountId()));
        Account second = ledger.lock(session, Math.max(oldAccountId, data.accountId()));
        Account account = first.getId() == data.accountId() ? first : second;
        // The old money out goes first, so the check below sees the account without it
        accountTransactions.findBySource(session, AccountTransactionSource.SALARY, p.getId())
                .forEach(t -> accountTransactions.delete(session, t));
        session.flush();
        BigDecimal available = ledger.availableOn(session, account.getId(), data.date());
        if (available.compareTo(data.amount()) < 0) {
            // The old entry is already removed: roll back
            throw new Abort(new Result(Status.INSUFFICIENT_BALANCE, available.max(BigDecimal.ZERO)));
        }
        fill(p, account, data);
        post(session, p);
        String now = describe(p);
        if (!old.equals(now)) {
            auditService.updated(session, PAYMENT, p.getId(), old, now);
        }
        return Result.of(Status.SAVED);
    }

    /** Deletes a salary payment and its money out (the account gets the money back). */
    public Status deletePayment(long id) {
        return TransactionHelper.inTransaction(session -> {
            SalaryPayment p = repository.findPayment(session, id).orElse(null);
            if (p == null) {
                return Status.NOT_FOUND;
            }
            employeeRepository.lock(session, p.getEmployee().getId());
            ledger.lock(session, p.getAccount().getId());
            String old = describe(p);
            accountTransactions.findBySource(session, AccountTransactionSource.SALARY, id)
                    .forEach(t -> accountTransactions.delete(session, t));
            repository.deletePayment(session, p);
            auditService.deleted(session, PAYMENT, id, old);
            return Status.SAVED;
        });
    }

    /** One employee's payments for one month, oldest first, with the account loaded. */
    public List<SalaryPayment> payments(long employeeId, YearMonth month) {
        return TransactionHelper.inTransaction(session ->
                repository.paymentsOf(session, employeeId, month.getYear(), month.getMonthValue()));
    }

    private void post(Session session, SalaryPayment p) {
        ledger.post(session, p.getAccount().getId(), p.getDate(), AccountTransactionType.DEBIT, p.getAmount(),
                AccountTransactionSource.SALARY, p.getId(), p.getNote());
    }

    private static void fill(SalaryPayment p, Account account, PaymentData data) {
        p.setYear(data.month().getYear());
        p.setMonth(data.month().getMonthValue());
        p.setDate(data.date());
        p.setAmount(data.amount());
        p.setAccount(account);
        p.setNote(note(data.note()));
        p.setEmergency(data.emergency());
    }

    // ---------------------------------------------------------------- history (14.5) and month sheet (16.10)

    /** An employee's months (processed or with payments), newest first, and every payment. */
    public History history(long employeeId) {
        return TransactionHelper.inTransaction(session -> {
            Employee e = employeeRepository.findById(session, employeeId)
                    .orElseThrow(() -> new IllegalStateException("Employee not found: " + employeeId));
            List<SalaryPayment> payments = repository.paymentsOf(session, employeeId);
            Map<YearMonth, BigDecimal> paidByMonth = new HashMap<>();
            payments.forEach(p -> paidByMonth.merge(YearMonth.of(p.getYear(), p.getMonth()), p.getAmount(),
                    BigDecimal::add));
            Map<YearMonth, SalaryProcessing> sheets = sheets(session, employeeId);
            Chain chain = chain(nets(sheets), paidByMonth, null);

            List<MonthRow> rows = new ArrayList<>();
            BigDecimal totalNet = BigDecimal.ZERO;
            BigDecimal totalPaid = BigDecimal.ZERO;
            for (Step st : chain.steps().values()) {
                SalaryProcessing s = sheets.get(st.month());
                totalPaid = totalPaid.add(st.paid());
                if (s == null) {
                    rows.add(new MonthRow(st.month(), null, null, null, null, null, st.carryIn(), st.paid(), null,
                            null));
                } else {
                    rows.add(new MonthRow(st.month(), s, s.getGrossSalary(), s.getDeductions(), s.getBonus(),
                            s.getNetSalary(), st.carryIn(), st.paid(), st.due(), st.advanceLeft()));
                    totalNet = totalNet.add(s.getNetSalary());
                }
            }
            rows.sort(Comparator.comparing(MonthRow::month).reversed());
            return new History(e, rows, payments, totalNet, totalPaid, chain.totalDue(), chain.advance());
        });
    }

    /** Every employee's salary for one month (active ones, plus anyone with a sheet or payment that month). */
    public MonthSheet monthSheet(YearMonth month) {
        int working = workingDays(month);
        return TransactionHelper.inTransaction(session -> {
            int y = month.getYear();
            int m = month.getMonthValue();
            Map<Long, SalaryProcessing> sheets = new HashMap<>();
            repository.sheetsOfMonth(session, y, m).forEach(s -> sheets.put(s.getEmployee().getId(), s));
            Map<Long, BigDecimal> paid = repository.paidByEmployee(session, y, m);
            Map<Long, Map<YearMonth, BigDecimal>> netsUpTo = repository.netsUpTo(session, y, m);
            Map<Long, Map<YearMonth, BigDecimal>> paidUpTo = repository.paidUpTo(session, y, m);
            Map<Long, Employee> employees = new LinkedHashMap<>();
            employeeRepository.findByStatus(session, EmployeeStatus.ACTIVE).forEach(e -> employees.put(e.getId(), e));
            sheets.values().forEach(s -> employees.putIfAbsent(s.getEmployee().getId(), s.getEmployee()));
            List<Long> missing = paid.keySet().stream().filter(id -> !employees.containsKey(id)).toList();
            employeeRepository.findByIds(session, missing).forEach(e -> employees.put(e.getId(), e));

            List<SheetRow> rows = new ArrayList<>();
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal deductions = BigDecimal.ZERO;
            BigDecimal bonus = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            BigDecimal carry = BigDecimal.ZERO;
            BigDecimal totalPaid = BigDecimal.ZERO;
            BigDecimal due = BigDecimal.ZERO;
            for (Employee e : employees.values()) {
                SalaryProcessing s = sheets.get(e.getId());
                Step st = chain(netsUpTo.getOrDefault(e.getId(), Map.of()), paidUpTo.getOrDefault(e.getId(), Map.of()),
                        month).steps().get(month);
                totalPaid = totalPaid.add(st.paid());
                carry = carry.add(st.carryIn());
                if (s != null) {
                    gross = gross.add(s.getGrossSalary());
                    deductions = deductions.add(s.getDeductions());
                    bonus = bonus.add(s.getBonus());
                    net = net.add(s.getNetSalary());
                    due = due.add(st.due());
                }
                rows.add(new SheetRow(e, s, st.carryIn(), st.paid(), st.due(), st.advanceLeft()));
            }
            rows.sort(Comparator.comparing((SheetRow r) -> r.employee().getName(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(r -> r.employee().getId()));
            return new MonthSheet(month, working, rows, gross, deductions, bonus, net, carry, totalPaid, due);
        });
    }

    // ---------------------------------------------------------------- helpers

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String note(String note) {
        String n = note == null ? "" : note.trim();
        return n.isEmpty() ? null : n.length() > NOTE_MAX ? n.substring(0, NOTE_MAX) : n;
    }

    private static String describe(SalaryProcessing s) {
        return "salary employee=" + s.getEmployee().getId() + " month=" + s.getYear() + "-" + s.getMonth()
                + " working=" + s.getWorkingDays() + " present=" + s.getPresentDays() + " leave=" + s.getLeaveDays()
                + " units=" + (s.getWorkedUnits() == null ? null : s.getWorkedUnits().toPlainString())
                + " gross=" + s.getGrossSalary().toPlainString() + " deductions=" + s.getDeductions().toPlainString()
                + " bonus=" + s.getBonus().toPlainString() + " net=" + s.getNetSalary().toPlainString()
                + (s.getNote() == null ? "" : " note=" + s.getNote());
    }

    private static String describe(SalaryPayment p) {
        return "salary payment employee=" + p.getEmployee().getId() + " month=" + p.getYear() + "-" + p.getMonth()
                + " date=" + p.getDate() + " account=" + p.getAccount().getNameEn()
                + " amount=" + p.getAmount().toPlainString() + (p.isEmergency() ? " emergency" : "")
                + (p.getNote() == null ? "" : " note=" + p.getNote());
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
