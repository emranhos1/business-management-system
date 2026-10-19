package com.eh.bms.service;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.Expense;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.repository.AccountTransactionRepository;
import com.eh.bms.repository.ExpenseCategoryRepository;
import com.eh.bms.repository.ExpenseRepository;
import com.eh.bms.repository.ExpenseRepository.Filter;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    /** One category in the chosen dates: its total and its expenses, newest first. */
    public record Group(ExpenseCategory category, BigDecimal total, List<Expense> expenses) {
    }

    /** Expenses of a date range grouped by category (largest total first), their total and the number of expenses. */
    public record GroupedExpenses(List<Group> groups, BigDecimal total, int count) {
    }

    private static final int NOTE_MAX = 255;

    private final ExpenseRepository repository = new ExpenseRepository();
    private final ExpenseCategoryRepository categoryRepository = new ExpenseCategoryRepository();
    private final AccountTransactionRepository accountTransactions = new AccountTransactionRepository();
    private final AccountLedgerService ledger = new AccountLedgerService();
    private final AuditService auditService = new AuditService();

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

    /**
     * Expenses from {@code from} to {@code to} (null = no limit on that side) grouped by category, largest total
     * first (13.3, 13.4); one query, the grouping is done here.
     */
    public GroupedExpenses groups(LocalDate from, LocalDate to) {
        List<Expense> expenses = TransactionHelper.inTransaction(session ->
                repository.list(session, new Filter(null, from, to)));
        // Newest first within each category, as the query returns them
        Map<Long, List<Expense>> byCategory = new LinkedHashMap<>();
        for (Expense e : expenses) {
            byCategory.computeIfAbsent(e.getCategory().getId(), id -> new ArrayList<>()).add(e);
        }
        List<Group> groups = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (List<Expense> list : byCategory.values()) {
            BigDecimal sum = list.stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(new Group(list.get(0).getCategory(), sum, List.copyOf(list)));
            total = total.add(sum);
        }
        groups.sort(Comparator.comparing(Group::total).reversed());
        return new GroupedExpenses(groups, total, expenses.size());
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
