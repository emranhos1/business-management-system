package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.SalaryPayment;
import com.eh.bms.model.SalaryProcessing;
import com.eh.bms.model.SalaryType;
import com.eh.bms.model.User;
import com.eh.bms.repository.AccountTransactionRepository;
import com.eh.bms.repository.SalaryRepository;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Salary of every user (14.3 - 14.5, N12: users and employees are one; admin only, on the users page).
 * <ul>
 * <li>One popup works out a month and pays in the same save ({@link #save}). The month's sheet
 * ({@code salary_processing}): gross by salary type (monthly = base salary; daily = days worked x rate; hourly =
 * hours x rate, days / hours typed in), net = gross - deductions + bonus, never below 0. A deduction needs its
 * reason. Saving a month again changes its sheet.</li>
 * <li>Payments ({@code salary_payments}): each one is an account DEBIT with source SALARY. A payment never goes
 * above the month's due, except an emergency advance; money out never goes above what the account holds on that
 * date.</li>
 * <li>Carry forward: whatever a month got above its net (an emergency advance, or a net lowered after paying) counts
 * as paid for the next months, oldest first ({@link #chain}). A due is never below 0; the rest is "advance left".</li>
 * </ul>
 * Lock order: user row, then account rows (by id).
 */
public class SalaryService {

    public enum Status {
        SAVED,
        NOT_FOUND,
        /** The month is after the current one. */
        FUTURE_MONTH,
        /** The user has no salary type or amount yet (the admin sets it in the user form). */
        NO_SALARY,
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
     * The month as typed. {@code workedUnits}: days (daily) or hours (hourly), required for those, unused for monthly.
     * {@code deductions} / {@code bonus} null = 0. {@code note}: the reason, required when there is a deduction.
     */
    public record MonthData(long userId, YearMonth month, BigDecimal workedUnits, BigDecimal deductions,
                            BigDecimal bonus, String note) {
    }

    /** Money paid in the same save. {@code emergency}: an emergency advance, allowed above the month's due. */
    public record Pay(LocalDate date, BigDecimal amount, long accountId, boolean emergency) {
    }

    /**
     * What the popup opens with for a user and month: the saved sheet, else empty values (a monthly salary's gross
     * is its base). {@code paid}: paid for the month so far; {@code carryIn}: advance carried from earlier months.
     */
    public record Draft(User user, YearMonth month, SalaryProcessing sheet, BigDecimal workedUnits, BigDecimal gross,
                        BigDecimal deductions, BigDecimal bonus, BigDecimal net, String note, BigDecimal paid,
                        BigDecimal carryIn) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /**
     * A month's money for one user. {@code carryIn}: advance carried from earlier months. When processed,
     * net - carryIn - paid = due - advanceLeft (both never below 0). {@code net}, {@code due} and
     * {@code advanceLeft} are null when the month is not processed (then {@code paid} is all advance).
     */
    public record MonthDue(YearMonth month, SalaryProcessing sheet, BigDecimal net, BigDecimal paid,
                           BigDecimal carryIn, BigDecimal due, BigDecimal advanceLeft) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /** One month in a user's history; sheet values (and due, advanceLeft) are null when only advances were paid. */
    public record MonthRow(YearMonth month, SalaryProcessing sheet, BigDecimal gross, BigDecimal deductions,
                           BigDecimal bonus, BigDecimal net, BigDecimal carryIn, BigDecimal paid, BigDecimal due,
                           BigDecimal advanceLeft) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /**
     * A user's salary history, newest month first, and all payments, newest first. {@code totalDue} sums the
     * processed months' dues; {@code totalAdvance} is advance not earned yet (carried past the last processed month,
     * plus paid for months not processed). totalNet - totalPaid = totalDue - totalAdvance.
     */
    public record History(User user, List<MonthRow> months, List<SalaryPayment> payments, BigDecimal totalNet,
                          BigDecimal totalPaid, BigDecimal totalDue, BigDecimal totalAdvance) {
    }

    /** One user on the month sheet; {@code sheet} null = not processed (then due and advanceLeft are null). */
    public record SheetRow(User user, SalaryProcessing sheet, BigDecimal carryIn, BigDecimal paid,
                           BigDecimal due, BigDecimal advanceLeft) {
        public boolean processed() {
            return sheet != null;
        }
    }

    /**
     * All users' salary for one month (16.10): active users with a salary plus anyone with a sheet or payment that
     * month, sorted by name. Totals sum the processed rows, except {@code totalPaid} (every payment) and
     * {@code totalCarry} (advance carried in, every row).
     */
    public record MonthSheet(YearMonth month, List<SheetRow> rows, BigDecimal totalGross,
                             BigDecimal totalDeductions, BigDecimal totalBonus, BigDecimal totalNet,
                             BigDecimal totalCarry, BigDecimal totalPaid, BigDecimal totalDue) {
    }

    /** Money still open with a user: due on processed months, and advance not earned yet. */
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
    private final UserRepository userRepository = new UserRepository();
    private final AccountTransactionRepository accountTransactions = new AccountTransactionRepository();
    private final AccountLedgerService ledger = new AccountLedgerService();
    private final AuditService auditService = new AuditService();

    // ---------------------------------------------------------------- gross and net

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

    // ---------------------------------------------------------------- the salary popup

    /** What the salary popup opens with for this user and month. */
    public Draft draft(long userId, YearMonth month) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            User u = userRepository.findById(session, userId)
                    .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
            MonthDue due = monthDue(session, userId, month);
            SalaryProcessing s = due.sheet();
            if (s != null) {
                return new Draft(u, month, s, s.getWorkedUnits(), s.getGrossSalary(), s.getDeductions(), s.getBonus(),
                        s.getNetSalary(), s.getNote(), due.paid(), due.carryIn());
            }
            BigDecimal gross = u.hasSalary() && u.getSalaryType() == SalaryType.MONTHLY
                    ? gross(u.getSalaryType(), u.getBaseSalary(), null) : null;
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new Draft(u, month, null, null, gross, zero, zero, gross == null ? null : net(gross, zero, zero),
                    null, due.paid(), due.carryIn());
        });
    }

    /**
     * Works out (or works out again) the user's month with the user's current salary type and amount and, when
     * {@code pay} is given, pays money for it, all in one transaction. Paid above the net carries to the next months.
     *
     * @throws IllegalArgumentException when a value is missing or out of range (the form checks these first)
     */
    public Result save(MonthData data, Pay pay) {
        requireAdmin();
        Objects.requireNonNull(data.month(), "month");
        if (data.month().isAfter(YearMonth.now())) {
            return Result.of(Status.FUTURE_MONTH);
        }
        BigDecimal deductions = zero(data.deductions()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal bonus = zero(data.bonus()).setScale(2, RoundingMode.HALF_UP);
        String note = note(data.note());
        if (deductions.signum() < 0 || bonus.signum() < 0) {
            throw new IllegalArgumentException("Deductions and bonus cannot be below 0");
        }
        if (deductions.signum() > 0 && note == null) {
            throw new IllegalArgumentException("A deduction needs its reason");
        }
        if (data.workedUnits() != null
                && (data.workedUnits().signum() < 0 || data.workedUnits().compareTo(UNITS_MAX) > 0)) {
            throw new IllegalArgumentException("Worked days / hours out of range");
        }
        if (pay != null) {
            Objects.requireNonNull(pay.date(), "date");
            if (pay.amount() == null || pay.amount().signum() <= 0) {
                throw new IllegalArgumentException("Amount must be more than 0");
            }
        }
        int days = data.month().lengthOfMonth();
        try {
            return TransactionHelper.inTransaction(session -> {
                User u = userRepository.lock(session, data.userId()).orElse(null);
                if (u == null) {
                    return Result.of(Status.NOT_FOUND);
                }
                if (!u.hasSalary()) {
                    return Result.of(Status.NO_SALARY);
                }
                if (u.getSalaryType() != SalaryType.MONTHLY) {
                    if (data.workedUnits() == null) {
                        throw new IllegalArgumentException("Worked days / hours are required");
                    }
                    if (u.getSalaryType() == SalaryType.DAILY && data.workedUnits().compareTo(BigDecimal.valueOf(days)) > 0) {
                        throw new IllegalArgumentException("Worked days cannot be more than " + days);
                    }
                }
                BigDecimal units = u.getSalaryType() == SalaryType.MONTHLY || data.workedUnits() == null ? null
                        : data.workedUnits().setScale(2, RoundingMode.HALF_UP);
                BigDecimal gross = gross(u.getSalaryType(), u.getBaseSalary(), units);
                BigDecimal net = net(gross, deductions, bonus);
                if (net.signum() < 0) {
                    return Result.of(Status.NEGATIVE_NET);
                }
                writeSheet(session, u, data.month(), units, gross, deductions, bonus, net, note);
                if (pay != null) {
                    session.flush();
                    Result paid = pay(session, u, data.month(), pay);
                    if (paid.status() != Status.SAVED) {
                        // The sheet is already written: roll it back too
                        throw new Abort(paid);
                    }
                }
                return Result.of(Status.SAVED);
            });
        } catch (Abort a) {
            return a.result;
        }
    }

    private void writeSheet(Session session, User u, YearMonth month, BigDecimal units, BigDecimal gross,
                            BigDecimal deductions, BigDecimal bonus, BigDecimal net, String note) {
        int y = month.getYear();
        int m = month.getMonthValue();
        SalaryProcessing s = repository.findSheet(session, u.getId(), y, m).orElse(null);
        boolean added = s == null;
        String old = added ? null : describe(s);
        if (added) {
            s = new SalaryProcessing();
            s.setUser(u);
            s.setYear(y);
            s.setMonth(m);
        }
        s.setWorkedUnits(units);
        s.setGrossSalary(gross);
        s.setDeductions(deductions);
        s.setBonus(bonus);
        s.setNetSalary(net);
        s.setNote(note);
        if (added) {
            repository.saveSheet(session, s);
            auditService.created(session, SHEET, s.getId(), describe(s));
        } else {
            String now = describe(s);
            if (!old.equals(now)) {
                auditService.updated(session, SHEET, s.getId(), old, now);
            }
        }
    }

    /** Pays for the month (its sheet already saved): never above its due unless an emergency advance. */
    private Result pay(Session session, User u, YearMonth month, Pay pay) {
        MonthDue due = monthDue(session, u.getId(), month);
        if (!pay.emergency() && due.processed() && due.due().compareTo(pay.amount()) < 0) {
            return new Result(Status.OVER_NET, due.due());
        }
        Account account = ledger.lock(session, pay.accountId());
        BigDecimal available = ledger.availableOn(session, account.getId(), pay.date());
        if (available.compareTo(pay.amount()) < 0) {
            return new Result(Status.INSUFFICIENT_BALANCE, available.max(BigDecimal.ZERO));
        }
        SalaryPayment p = new SalaryPayment();
        p.setUser(u);
        p.setYear(month.getYear());
        p.setMonth(month.getMonthValue());
        p.setDate(pay.date());
        p.setAmount(pay.amount());
        p.setAccount(account);
        p.setEmergency(pay.emergency());
        if (UserSession.isLoggedIn()) {
            p.setCreatedBy(session.getReference(User.class, UserSession.get().getUserId()));
        }
        repository.savePayment(session, p);
        ledger.post(session, account.getId(), p.getDate(), AccountTransactionType.DEBIT, p.getAmount(),
                AccountTransactionSource.SALARY, p.getId(), null);
        auditService.created(session, PAYMENT, p.getId(), describe(p));
        return Result.of(Status.SAVED);
    }

    /** Deletes a salary payment and its money out (the account gets the money back). */
    public Status deletePayment(long id) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            SalaryPayment p = repository.findPayment(session, id).orElse(null);
            if (p == null) {
                return Status.NOT_FOUND;
            }
            userRepository.lock(session, p.getUser().getId());
            ledger.lock(session, p.getAccount().getId());
            String old = describe(p);
            accountTransactions.findBySource(session, AccountTransactionSource.SALARY, id)
                    .forEach(t -> accountTransactions.delete(session, t));
            repository.deletePayment(session, p);
            auditService.deleted(session, PAYMENT, id, old);
            return Status.SAVED;
        });
    }

    // ---------------------------------------------------------------- carry chain

    private MonthDue monthDue(Session session, long userId, YearMonth month) {
        Map<YearMonth, SalaryProcessing> sheets = sheets(session, userId);
        Step st = chain(nets(sheets), repository.paidByMonth(session, userId, null), month).steps().get(month);
        return new MonthDue(month, sheets.get(month), st.net(), st.paid(), st.carryIn(), st.due(), st.advanceLeft());
    }

    /** Due on processed months and advance not earned yet (a user with either cannot be made inactive). */
    OpenMoney openMoney(Session session, long userId) {
        Chain c = chain(nets(sheets(session, userId)), repository.paidByMonth(session, userId, null), null);
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

    private Map<YearMonth, SalaryProcessing> sheets(Session session, long userId) {
        Map<YearMonth, SalaryProcessing> sheets = new HashMap<>();
        repository.sheetsOf(session, userId).forEach(s -> sheets.put(YearMonth.of(s.getYear(), s.getMonth()), s));
        return sheets;
    }

    private static Map<YearMonth, BigDecimal> nets(Map<YearMonth, SalaryProcessing> sheets) {
        Map<YearMonth, BigDecimal> nets = new HashMap<>();
        sheets.forEach((m, s) -> nets.put(m, s.getNetSalary()));
        return nets;
    }

    // ---------------------------------------------------------------- history (14.5) and month sheet (16.10)

    /** A user's months (processed or with payments), newest first, and every payment. */
    public History history(long userId) {
        requireAdmin();
        return TransactionHelper.inTransaction(session -> {
            User u = userRepository.findById(session, userId)
                    .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
            List<SalaryPayment> payments = repository.paymentsOf(session, userId);
            Map<YearMonth, BigDecimal> paidByMonth = new HashMap<>();
            payments.forEach(p -> paidByMonth.merge(YearMonth.of(p.getYear(), p.getMonth()), p.getAmount(),
                    BigDecimal::add));
            Map<YearMonth, SalaryProcessing> sheets = sheets(session, userId);
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
            return new History(u, rows, payments, totalNet, totalPaid, chain.totalDue(), chain.advance());
        });
    }

    /** Every user's salary for one month (active users with a salary, plus anyone with a sheet or payment). */
    public MonthSheet monthSheet(YearMonth month) {
        return TransactionHelper.inTransaction(session -> {
            int y = month.getYear();
            int m = month.getMonthValue();
            Map<Long, SalaryProcessing> sheets = new HashMap<>();
            repository.sheetsOfMonth(session, y, m).forEach(s -> sheets.put(s.getUser().getId(), s));
            Map<Long, BigDecimal> paid = repository.paidByUser(session, y, m);
            Map<Long, Map<YearMonth, BigDecimal>> netsUpTo = repository.netsUpTo(session, y, m);
            Map<Long, Map<YearMonth, BigDecimal>> paidUpTo = repository.paidUpTo(session, y, m);
            Map<Long, User> users = new LinkedHashMap<>();
            userRepository.activeWithSalary(session).forEach(u -> users.put(u.getId(), u));
            sheets.values().forEach(s -> users.putIfAbsent(s.getUser().getId(), s.getUser()));
            List<Long> missing = paid.keySet().stream().filter(id -> !users.containsKey(id)).toList();
            userRepository.findByIds(session, missing).forEach(u -> users.put(u.getId(), u));

            List<SheetRow> rows = new ArrayList<>();
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal deductions = BigDecimal.ZERO;
            BigDecimal bonus = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            BigDecimal carry = BigDecimal.ZERO;
            BigDecimal totalPaid = BigDecimal.ZERO;
            BigDecimal due = BigDecimal.ZERO;
            for (User u : users.values()) {
                SalaryProcessing s = sheets.get(u.getId());
                Step st = chain(netsUpTo.getOrDefault(u.getId(), Map.of()), paidUpTo.getOrDefault(u.getId(), Map.of()),
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
                rows.add(new SheetRow(u, s, st.carryIn(), st.paid(), st.due(), st.advanceLeft()));
            }
            rows.sort(Comparator.comparing((SheetRow r) -> r.user().getName(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(r -> r.user().getId()));
            return new MonthSheet(month, rows, gross, deductions, bonus, net, carry, totalPaid, due);
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Salary is on the users page: the admin's alone, whatever a screen shows. */
    private static void requireAdmin() {
        if (!UserSession.isLoggedIn() || !UserSession.get().isAdmin()) {
            throw new IllegalStateException("Only an admin may manage salary");
        }
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String note(String note) {
        String n = note == null ? "" : note.trim();
        return n.isEmpty() ? null : n.length() > NOTE_MAX ? n.substring(0, NOTE_MAX) : n;
    }

    private static String describe(SalaryProcessing s) {
        return "salary user=" + s.getUser().getId() + " month=" + s.getYear() + "-" + s.getMonth()
                + " units=" + (s.getWorkedUnits() == null ? null : s.getWorkedUnits().toPlainString())
                + " gross=" + s.getGrossSalary().toPlainString() + " deductions=" + s.getDeductions().toPlainString()
                + " bonus=" + s.getBonus().toPlainString() + " net=" + s.getNetSalary().toPlainString()
                + (s.getNote() == null ? "" : " note=" + s.getNote());
    }

    private static String describe(SalaryPayment p) {
        return "salary payment user=" + p.getUser().getId() + " month=" + p.getYear() + "-" + p.getMonth()
                + " date=" + p.getDate() + " account=" + p.getAccount().getNameEn()
                + " amount=" + p.getAmount().toPlainString() + (p.isEmergency() ? " emergency" : "");
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
