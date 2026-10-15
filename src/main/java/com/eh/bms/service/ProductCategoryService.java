package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.repository.ProductCategoryRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Product categories (8.1). Each has a Bangla and an English name. */
public class ProductCategoryService {

    public enum SaveResult {
        SAVED, DUPLICATE_NAME_BN, DUPLICATE_NAME_EN
    }

    public enum DeleteResult {
        DELETED, IN_USE, NOT_FOUND
    }

    /** Values from the form. {@code id} is null for a new category. */
    public record CategoryData(Long id, String nameBn, String nameEn) {
    }

    private final ProductCategoryRepository repository = new ProductCategoryRepository();
    private final AuditService auditService = new AuditService();

    public PageResult<ProductCategory> page(int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, offset, limit), repository.count(session)));
    }

    /** Every category, sorted by name (for pick lists, e.g. the product form). */
    public List<ProductCategory> all() {
        return TransactionHelper.inTransaction(repository::all);
    }

    /** Number of products in each of the given categories (missing id = none). */
    public Map<Long, Long> productCounts(List<ProductCategory> categories) {
        List<Long> ids = categories.stream().map(ProductCategory::getId).toList();
        return TransactionHelper.inTransaction(session -> repository.productCounts(session, ids));
    }

    public SaveResult save(CategoryData data) {
        return TransactionHelper.inTransaction(session -> {
            String nameBn = data.nameBn().trim();
            String nameEn = data.nameEn().trim();
            if (repository.existsByName(session, nameBn, data.id())) {
                return SaveResult.DUPLICATE_NAME_BN;
            }
            if (repository.existsByNameEn(session, nameEn, data.id())) {
                return SaveResult.DUPLICATE_NAME_EN;
            }
            if (data.id() == null) {
                ProductCategory c = new ProductCategory();
                c.setName(nameBn);
                c.setNameEn(nameEn);
                repository.save(session, c);
                auditService.created(session, AppModule.PRODUCT, c.getId(), describe(c));
                return SaveResult.SAVED;
            }
            ProductCategory c = repository.findById(session, data.id())
                    .orElseThrow(() -> new IllegalStateException("Product category not found: " + data.id()));
            String old = describe(c);
            c.setName(nameBn);
            c.setNameEn(nameEn);
            String now = describe(c);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, AppModule.PRODUCT, c.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** Deletes only a category no product uses. */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            ProductCategory c = repository.findById(session, id).orElse(null);
            if (c == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id)) {
                return DeleteResult.IN_USE;
            }
            String old = describe(c);
            repository.delete(session, c);
            auditService.deleted(session, AppModule.PRODUCT, id, old);
            return DeleteResult.DELETED;
        });
    }

    private static String describe(ProductCategory c) {
        return "productCategory: name=" + c.getName() + ", nameEn=" + c.getNameEn();
    }
}
