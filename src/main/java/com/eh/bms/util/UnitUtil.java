package com.eh.bms.util;

import com.eh.bms.model.Product;
import com.eh.bms.model.Unit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shows product units for every screen: the conversion ("১ বস্তা = ৫০ কেজি") and a quantity in both
 * units ("২.৫ বস্তা (১২৫ কেজি)"). Numbers in Bangla digits in Bangla mode; unit names from the
 * {@link Unit} list in the current language. The product's units must be loaded.
 */
public final class UnitUtil {

    /** Quantities are stored as DECIMAL(14,3). */
    private static final int QTY_DECIMALS = 3;

    private UnitUtil() {
    }

    /** Unit name in the current language; "" for null. */
    public static String name(Unit unit) {
        return unit == null ? "" : I18n.pick(unit.getNameBn(), unit.getNameEn());
    }

    /** "১ বস্তা = ৫০ কেজি"; "" when the product has no secondary unit or factor. */
    public static String conversionText(Product p) {
        return p == null ? "" : conversionText(name(p.getUnit()), name(p.getSecondaryUnit()), p.getConversionFactor());
    }

    /** "১ {unit} = {factor} {secondaryUnit}" from unit names; "" when any part is missing or the factor is not above 0. */
    public static String conversionText(String unit, String secondaryUnit, BigDecimal factor) {
        if (isBlank(unit) || isBlank(secondaryUnit) || factor == null || factor.signum() <= 0) {
            return "";
        }
        return I18n.digits("1") + " " + unit.trim() + " = " + number(factor) + " " + secondaryUnit.trim();
    }

    /**
     * "চাল (১ বস্তা = ৫০ কেজি)": the product name in the current language with its conversion, so same-name
     * products in other pack sizes can be told apart; just the name when it has no conversion.
     */
    public static String productName(Product p) {
        String name = I18n.pick(p.getName(), p.getNameEn());
        String conversion = conversionText(p);
        return conversion.isEmpty() ? name : name + " (" + conversion + ")";
    }

    /** "২ বস্তা (১০০ কেজি)" with a secondary unit, "১০ পিস" without one; "" for a null quantity. */
    public static String quantityText(BigDecimal qty, Product p) {
        if (qty == null) {
            return "";
        }
        String main = number(qty) + " " + name(p.getUnit());
        BigDecimal factor = p.getConversionFactor();
        if (p.getSecondaryUnit() == null || factor == null || factor.signum() <= 0) {
            return main;
        }
        BigDecimal secondary = qty.multiply(factor).setScale(QTY_DECIMALS, RoundingMode.HALF_UP);
        return main + " (" + number(secondary) + " " + name(p.getSecondaryUnit()) + ")";
    }

    /** A number without useless trailing zeros (50, 12.5, 0.25), in Bangla digits in Bangla mode. */
    public static String number(BigDecimal value) {
        return I18n.digits(value.stripTrailingZeros().toPlainString());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
