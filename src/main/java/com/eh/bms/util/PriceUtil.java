package com.eh.bms.util;

import com.eh.bms.model.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A product's sale price (8.6) for a customer type and a unit. */
public final class PriceUtil {

    private PriceUtil() {
    }

    /**
     * Price per main unit ({@code secondary} false) or per secondary unit ({@code secondary} true), wholesale or
     * retail. A blank secondary price is the main price ÷ conversion factor. Null when the product has no such
     * price (the sale screen then waits for the user to type one).
     */
    public static BigDecimal price(Product p, boolean wholesale, boolean secondary) {
        BigDecimal main = wholesale ? p.getWholesalePrice() : p.getRetailPrice();
        if (!secondary) {
            return main;
        }
        BigDecimal own = wholesale ? p.getWholesalePriceSecondary() : p.getRetailPriceSecondary();
        if (own != null) {
            return own;
        }
        BigDecimal factor = p.getConversionFactor();
        if (main == null || factor == null || factor.signum() <= 0) {
            return null;
        }
        return main.divide(factor, 2, RoundingMode.HALF_UP);
    }
}
