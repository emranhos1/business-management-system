package com.eh.bms.service;

import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.model.ExpenseCategoryType;
import com.eh.bms.repository.ExpenseCategoryRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.util.List;
import java.util.Objects;

/** Expense categories the user manages (13.1). */
public class ExpenseCategoryService {

    public enum SaveResult {
        SAVED, DUPLICATE_NAME, DUPLICATE_NAME_EN
    }

    public enum DeleteResult {
        DELETED, IN_USE, NOT_FOUND
    }

    /** Values from the form. {@code id} is null for a new category. */
    public record CategoryData(Long id, String name, String nameEn, ExpenseCategoryType type) {
    }

    private final ExpenseCategoryRepository repository = new ExpenseCategoryRepository();
    private final AuditService auditService = new AuditService();

    /** All categories, for the expense form and the filters. */
    public List<ExpenseCategory> all() {
        return TransactionHelper.inTransaction(repository::findAll);
    }

    public PageResult<ExpenseCategory> page(int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, offset, limit), repository.count(session)));
    }

    public SaveResult save(CategoryData data) {
        return TransactionHelper.inTransaction(session -> {
            String name = data.name().trim();
            String nameEn = data.nameEn().trim();
            if (repository.existsByName(session, name, data.id())) {
                return SaveResult.DUPLICATE_NAME;
            }
            if (repository.existsByNameEn(session, nameEn, data.id())) {
                return SaveResult.DUPLICATE_NAME_EN;
            }
            if (data.id() == null) {
                ExpenseCategory c = new ExpenseCategory();
                c.setName(name);
                c.setNameEn(nameEn);
                c.setType(data.type());
                repository.save(session, c);
                auditService.created(session, "EXPENSE_CATEGORY", c.getId(), describe(c));
                return SaveResult.SAVED;
            }
            ExpenseCategory c = repository.findById(session, data.id())
                    .orElseThrow(() -> new IllegalStateException("Expense category not found: " + data.id()));
            String old = describe(c);
            c.setName(name);
            c.setNameEn(nameEn);
            c.setType(data.type());
            String now = describe(c);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, "EXPENSE_CATEGORY", c.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** True if no expense uses the category, so {@link #delete} would delete it. */
    public boolean canDelete(long id) {
        return TransactionHelper.inTransaction(session -> !repository.isUsed(session, id));
    }

    /** Deletes only a category no expense uses. */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            ExpenseCategory c = repository.findById(session, id).orElse(null);
            if (c == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id)) {
                return DeleteResult.IN_USE;
            }
            String old = describe(c);
            repository.delete(session, c);
            auditService.deleted(session, "EXPENSE_CATEGORY", id, old);
            return DeleteResult.DELETED;
        });
    }

    private static String describe(ExpenseCategory c) {
        return "expenseCategory: name=" + c.getName() + ", nameEn=" + c.getNameEn() + ", type=" + c.getType();
    }
}
