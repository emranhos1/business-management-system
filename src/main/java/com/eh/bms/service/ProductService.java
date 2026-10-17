package com.eh.bms.service;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.Product;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.model.Unit;
import com.eh.bms.repository.ProductCategoryRepository;
import com.eh.bms.repository.ProductRepository;
import com.eh.bms.repository.UnitRepository;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.StringJoiner;

/** Products (8.2). Each has a Bangla and an English name, a category and a unit (optionally a secondary unit). */
public class ProductService {

    public enum SaveResult {
        SAVED, DUPLICATE_NAME_BN, DUPLICATE_NAME_EN, DUPLICATE_SKU,
        /** Unit, secondary unit or conversion changed on a product that has stock or history. */
        PACK_LOCKED
    }

    public enum DeleteResult {
        DELETED, IN_USE, NOT_FOUND
    }

    /**
     * Values from the form. {@code id} is null for a new product. Blank SKU is saved as null; null
     * {@code secondaryUnitId} = no secondary unit, and then the conversion factor is dropped.
     * Min stock 0 = no low-stock alert for the product. Prices may be null (not set); the secondary-unit
     * prices are dropped when there is no secondary unit.
     */
    public record ProductData(Long id, String nameBn, String nameEn, long categoryId, String sku, long unitId,
                              Long secondaryUnitId, BigDecimal conversionFactor, BigDecimal minStockAlert,
                              BigDecimal retailPrice, BigDecimal wholesalePrice,
                              BigDecimal retailPriceSecondary, BigDecimal wholesalePriceSecondary) {
    }

    private final ProductRepository repository = new ProductRepository();
    private final ProductCategoryRepository categoryRepository = new ProductCategoryRepository();
    private final UnitRepository unitRepository = new UnitRepository();
    private final AuditService auditService = new AuditService();

    public PageResult<Product> page(int offset, int limit) {
        return TransactionHelper.inTransaction(session ->
                new PageResult<>(repository.page(session, offset, limit), repository.count(session)));
    }

    /** One page of products whose name (Bangla or English) or SKU contains {@code text}; all when blank. */
    public PageResult<Product> search(String text, int offset, int limit) {
        return TransactionHelper.inTransaction(session -> new PageResult<>(
                repository.search(session, text, offset, limit), repository.countSearch(session, text)));
    }

    public SaveResult save(ProductData data) {
        return TransactionHelper.inTransaction(session -> {
            String nameBn = data.nameBn().trim();
            String nameEn = data.nameEn().trim();
            String sku = blankToNull(data.sku());
            // The same name is fine with another pack size; only the same name + same pack is a duplicate
            BigDecimal factor = data.secondaryUnitId() == null ? null : data.conversionFactor();
            if (repository.existsSamePack(session, nameBn, data.unitId(), data.secondaryUnitId(), factor, data.id())) {
                return SaveResult.DUPLICATE_NAME_BN;
            }
            if (repository.existsSamePackEn(session, nameEn, data.unitId(), data.secondaryUnitId(), factor, data.id())) {
                return SaveResult.DUPLICATE_NAME_EN;
            }
            if (sku != null && repository.existsBySku(session, sku, data.id())) {
                return SaveResult.DUPLICATE_SKU;
            }
            ProductCategory category = categoryRepository.findById(session, data.categoryId())
                    .orElseThrow(() -> new IllegalStateException("Product category not found: " + data.categoryId()));
            Unit unit = findUnit(session, data.unitId());
            Unit secondaryUnit = data.secondaryUnitId() == null ? null : findUnit(session, data.secondaryUnitId());

            Product p;
            String old = null;
            if (data.id() == null) {
                p = new Product();
            } else {
                p = repository.findById(session, data.id())
                        .orElseThrow(() -> new IllegalStateException("Product not found: " + data.id()));
                // Stock and past lines are kept in the main unit: a used product keeps its pack
                if (packChanged(p, unit, secondaryUnit, factor) && repository.isUsed(session, p.getId())) {
                    return SaveResult.PACK_LOCKED;
                }
                old = describe(p);
            }
            p.setName(nameBn);
            p.setNameEn(nameEn);
            p.setCategory(category);
            p.setSku(sku);
            p.setUnit(unit);
            p.setSecondaryUnit(secondaryUnit);
            p.setConversionFactor(secondaryUnit == null ? null : data.conversionFactor());
            p.setMinStockAlert(data.minStockAlert());
            p.setRetailPrice(data.retailPrice());
            p.setWholesalePrice(data.wholesalePrice());
            p.setRetailPriceSecondary(secondaryUnit == null ? null : data.retailPriceSecondary());
            p.setWholesalePriceSecondary(secondaryUnit == null ? null : data.wholesalePriceSecondary());

            if (data.id() == null) {
                repository.save(session, p);
                auditService.created(session, AppModule.PRODUCT, p.getId(), describe(p));
                return SaveResult.SAVED;
            }
            String now = describe(p);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, AppModule.PRODUCT, p.getId(), old, now);
            }
            return SaveResult.SAVED;
        });
    }

    /** True if the product has no stock entries and no transaction items, so {@link #delete} would delete it. */
    private static boolean packChanged(Product p, Unit unit, Unit secondary, BigDecimal factor) {
        long oldSecondary = p.getSecondaryUnit() == null ? -1 : p.getSecondaryUnit().getId();
        long newSecondary = secondary == null ? -1 : secondary.getId();
        boolean factorSame = p.getConversionFactor() == null ? factor == null
                : factor != null && p.getConversionFactor().compareTo(factor) == 0;
        return p.getUnit() == null || !p.getUnit().getId().equals(unit.getId())
                || oldSecondary != newSecondary || !factorSame;
    }

    public boolean canDelete(long id) {
        return TransactionHelper.inTransaction(session -> !repository.isUsed(session, id));
    }

    /** Deletes only a product with no stock entries and no transaction items. */
    public DeleteResult delete(long id) {
        return TransactionHelper.inTransaction(session -> {
            Product p = repository.findById(session, id).orElse(null);
            if (p == null) {
                return DeleteResult.NOT_FOUND;
            }
            if (repository.isUsed(session, id)) {
                return DeleteResult.IN_USE;
            }
            String old = describe(p);
            repository.delete(session, p);
            auditService.deleted(session, AppModule.PRODUCT, id, old);
            return DeleteResult.DELETED;
        });
    }

    private Unit findUnit(Session session, long id) {
        return unitRepository.findById(session, id)
                .orElseThrow(() -> new IllegalStateException("Unit not found: " + id));
    }

    private static String unitName(Unit unit) {
        return unit == null ? null : unit.getNameEn();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String describe(Product p) {
        return new StringJoiner(", ", "product: ", "")
                .add("name=" + p.getName())
                .add("nameEn=" + p.getNameEn())
                .add("category=" + (p.getCategory() == null ? null : p.getCategory().getNameEn()))
                .add("sku=" + p.getSku())
                .add("unit=" + unitName(p.getUnit()))
                .add("secondaryUnit=" + unitName(p.getSecondaryUnit()))
                .add("conversion=" + plain(p.getConversionFactor()))
                .add("minStock=" + plain(p.getMinStockAlert()))
                .add("retail=" + plain(p.getRetailPrice()))
                .add("wholesale=" + plain(p.getWholesalePrice()))
                .add("retailSecondary=" + plain(p.getRetailPriceSecondary()))
                .add("wholesaleSecondary=" + plain(p.getWholesalePriceSecondary()))
                .toString();
    }
}
