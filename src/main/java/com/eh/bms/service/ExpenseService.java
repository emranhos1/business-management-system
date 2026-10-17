package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.Expense;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.model.ExpenseCategoryType;
import com.eh.bms.repository.AccountTransactionRepository;
import com.eh.bms.repository.ExpenseCategoryRepository;
import com.eh.bms.repository.ExpenseRepository;
import com.eh.bms.repository.ExpenseRepository.Filter;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Expenses (13.2-13.4): each one is money out of an account (account ledger DEBIT, source EXPENSE), saved in one
 * transaction. An expense may be changed or deleted later (user decision); its ledger entry is then replaced or
 * removed with it. Money out never goes above what the account holds on that date.
 */
public class ExpenseService {

    public enum Status {
        SAVED, INSUFFICIENT_BALANCE, NOT_FOUND
    }

    /** {@code available} is set for INSUFFICIENT_BALANCE: the most the account can pay on that date. */
    public record Result(Status status, BigDecimal available) {
    }

    /** Values from the form. {@code id} is null for a new expense. */
    public record ExpenseData(Long id, long categoryId, long accountId, BigDecimal amount, LocalDate date,
                              String note) {
    }

    /** One category's total in a month. */
    public record SummaryRow(ExpenseCategory category, BigDecimal total) {
    }

    /** A month's expenses by category (largest first), the month's total and the total per category type. */
    public record Summary(List<SummaryRow> rows, BigDecimal total, Map<ExpenseCategoryType, BigDecimal> byType) {
    }

    private static final int NOTE_MAX = 255;

    private final ExpenseRepository repository = new ExpenseRepository();
    private final ExpenseCategoryRepository categoryRepository = new ExpenseCategoryRepository();
    private final AccountTransactionRepository accountTransactions = new AccountTransactionRepository();
    private final AccountLedgerService ledger = new AccountLedgerService();
    private final AuditService auditService = new AuditService();

    public PageResult<Expense> page(Filter filter, int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, filter, offset, limit), repository.count(session, filter)));
    }

    /** Sum of the expenses the filter finds. */
    public BigDecimal total(Filter filter) {
        return TransactionHelper.inTransaction(session -> repository.total(session, filter));
    }

    /** Adds ({@code id} null) or changes an expense, with its money out of the account. */
    public Result save(ExpenseData data) {
        if (data.amount() == null || data.amount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be more than 0");
        }
        Objects.requireNonNull(data.date(), "date");
        try {
            return TransactionHelper.inTransaction(session -> data.id() == null ? add(session, data) : change(session, data));
        } catch (Abort a) {
            return a.result;
        }
    }

    private Result add(Session session, ExpenseData data) {
        ExpenseCategory category = categoryRepository.findById(session, data.categoryId()).orElse(null);
        if (category == null) {
            return new Result(Status.NOT_FOUND, null);
        }
        Account account = ledger.lock(session, data.accountId());
        BigDecimal available = ledger.availableOn(session, account.getId(), data.date());
        if (available.compareTo(data.amount()) < 0) {
            return new Result(Status.INSUFFICIENT_BALANCE, available.max(BigDecimal.ZERO));
        }
        Expense e = new Expense();
        fill(e, category, account, data);
        repository.save(session, e);
        ledger.post(session, account.getId(), e.getDate(), AccountTransactionType.DEBIT, e.getAmount(),
                AccountTransactionSource.EXPENSE, e.getId(), e.getNote());
        auditService.created(session, AppModule.EXPENSE, e.getId(), describe(e));
        return new Result(Status.SAVED, null);
    }

    private Result change(Session session, ExpenseData data) {
        Expense e = repository.findById(session, data.id()).orElse(null);
        ExpenseCategory category = categoryRepository.findById(session, data.categoryId()).orElse(null);
        if (e == null || category == null) {
            return new Result(Status.NOT_FOUND, null);
        }
        String old = describe(e);
        // Both accounts in id order (the same order as transfers), so two saves cannot deadlock
        long oldAccountId = e.getAccount().getId();
        Account first = ledger.lock(session, Math.min(oldAccountId, data.accountId()));
        Account second = ledger.lock(session, Math.max(oldAccountId, data.accountId()));
        Account account = first.getId() == data.accountId() ? first : second;
        // The old money out goes first, so the check below sees the account without it
        accountTransactions.findBySource(session, AccountTransactionSource.EXPENSE, e.getId())
                .forEach(t -> accountTransactions.delete(session, t));
        session.flush();
        BigDecimal available = ledger.availableOn(session, account.getId(), data.date());
        if (available.compareTo(data.amount()) < 0) {
            // The old entry is already removed: roll back
            throw new Abort(new Result(Status.INSUFFICIENT_BALANCE, available.max(BigDecimal.ZERO)));
        }
        fill(e, category, account, data);
        ledger.post(session, account.getId(), e.getDate(), AccountTransactionType.DEBIT, e.getAmount(),
                AccountTransactionSource.EXPENSE, e.getId(), e.getNote());
        String now = describe(e);
        if (!old.equals(now)) {
            auditService.updated(session, AppModule.EXPENSE, e.getId(), old, now);
        }
        return new Result(Status.SAVED, null);
    }

    /** Deletes an expense and its money out (the account gets the money back). */
    public Status delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Expense e = repository.findById(session, id).orElse(null);
            if (e == null) {
                return Status.NOT_FOUND;
            }
            ledger.lock(session, e.getAccount().getId());
            String old = describe(e);
            accountTransactions.findBySource(session, AccountTransactionSource.EXPENSE, id)
                    .forEach(t -> accountTransactions.delete(session, t));
            repository.delete(session, e);
            auditService.deleted(session, AppModule.EXPENSE, id, old);
            return Status.SAVED;
        });
    }

    /** Year of the oldest expense (this year if there is none), for the month picker. */
    public int firstYear() {
        LocalDate first = TransactionHelper.inTransaction(repository::firstDate);
        return first == null ? LocalDate.now().getYear() : Math.min(first.getYear(), LocalDate.now().getYear());
    }

    /** The month's expenses per category, largest first (13.4). */
    public Summary monthSummary(YearMonth month) {
        return TransactionHelper.inTransaction(session -> {
            Map<Long, ExpenseCategory> categories = new HashMap<>();
            categoryRepository.findAll(session).forEach(c -> categories.put(c.getId(), c));
            List<SummaryRow> rows = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            Map<ExpenseCategoryType, BigDecimal> byType = new EnumMap<>(ExpenseCategoryType.class);
            for (ExpenseCategoryType t : ExpenseCategoryType.values()) {
                byType.put(t, BigDecimal.ZERO);
            }
            for (Object[] r : repository.totalsByCategory(session, month.atDay(1), month.atEndOfMonth())) {
                ExpenseCategory c = categories.get((Long) r[0]);
                BigDecimal sum = (BigDecimal) r[1];
                rows.add(new SummaryRow(c, sum));
                total = total.add(sum);
                byType.merge(c.getType(), sum, BigDecimal::add);
            }
            rows.sort(Comparator.comparing(SummaryRow::total).reversed());
            return new Summary(rows, total, byType);
        });
    }

    private static void fill(Expense e, ExpenseCategory category, Account account, ExpenseData data) {
        e.setCategory(category);
        e.setAccount(account);
        e.setAmount(data.amount());
        e.setDate(data.date());
        String note = data.note() == null ? "" : data.note().trim();
        e.setNote(note.isEmpty() ? null : note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note);
    }

    private static String describe(Expense e) {
        return "expense date=" + e.getDate() + " category=" + e.getCategory().getNameEn()
                + " account=" + e.getAccount().getNameEn() + " amount=" + e.getAmount().toPlainString()
                + (e.getNote() == null ? "" : " note=" + e.getNote());
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
