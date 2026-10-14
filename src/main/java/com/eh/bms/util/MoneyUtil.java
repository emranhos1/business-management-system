package com.eh.bms.util;

import com.eh.bms.config.DataSeeder;
import com.eh.bms.model.BusinessProfile;
import com.eh.bms.service.BusinessProfileService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shows money for every screen: currency symbol + Bangladeshi grouping (12,34,567.00).
 * Bangla mode: the Bangla symbol from Settings and Bangla digits (৳ ১২,৩৪,৫৬৭.০০).
 * English mode: the English symbol from Settings (Tk. 12,34,567.00).
 * Symbols are read once; call {@link #reload()} after the business profile is saved.
 */
public final class MoneyUtil {

    private static final Logger LOG = Logger.getLogger(MoneyUtil.class.getName());
    private static final String DEFAULT_BANGLA = "৳";

    private static volatile String bangla;
    private static volatile String english;

    private MoneyUtil() {
    }

    public static String format(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        return currency() + " " + I18n.digits(group(amount.setScale(2, RoundingMode.HALF_UP)));
    }

    /** Re-reads both currency symbols from the business profile. */
    public static void reload() {
        try {
            BusinessProfile p = new BusinessProfileService().get();
            bangla = p.getCurrencySymbol();
            english = englishOf(p);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read currency symbols", e);
            bangla = DEFAULT_BANGLA;
            english = DataSeeder.DEFAULT_CURRENCY_EN;
        }
    }

    /** English symbol of the profile; the default when an older row has none. */
    public static String englishOf(BusinessProfile p) {
        String s = p.getCurrencySymbolEn();
        return s == null || s.isBlank() ? DataSeeder.DEFAULT_CURRENCY_EN : s;
    }

    private static String currency() {
        if (bangla == null || english == null) {
            reload();
        }
        return I18n.ENGLISH.equals(I18n.getLocale()) ? english : bangla;
    }

    /** 1234567.00 → 12,34,567.00 (last 3 digits, then groups of 2). */
    static String group(BigDecimal amount) {
        String plain = amount.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);

        StringBuilder sb = new StringBuilder();
        int len = whole.length();
        if (len <= 3) {
            sb.append(whole);
        } else {
            String head = whole.substring(0, len - 3);
            int first = head.length() % 2;
            if (first > 0) {
                sb.append(head, 0, first).append(',');
            }
            for (int i = first; i < head.length(); i += 2) {
                sb.append(head, i, i + 2).append(',');
            }
            sb.append(whole.substring(len - 3));
        }
        return (amount.signum() < 0 ? "-" : "") + sb + fraction;
    }
}
