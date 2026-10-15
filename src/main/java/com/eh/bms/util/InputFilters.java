package com.eh.bms.util;

import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;

import java.util.regex.Pattern;

/**
 * Input rules for text fields. The field refuses any key that breaks the rule,
 * so wrong characters never appear. Bangla digits (০-৯) are turned into 0-9 as they are typed,
 * so numbers are always stored the same way.
 */
public final class InputFilters {

    private static final char BANGLA_ZERO = '০';

    private InputFilters() {
    }

    /** At most {@code maxLength} characters. */
    public static void maxLength(TextInputControl field, int maxLength) {
        field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= maxLength ? change : null));
    }

    /** Digits only (e.g. phone number), at most {@code maxLength}. */
    public static void digits(TextInputControl field, int maxLength) {
        Pattern allowed = Pattern.compile("\\d{0," + maxLength + "}");
        field.setTextFormatter(new TextFormatter<String>(change -> {
            change.setText(toAsciiDigits(change.getText()));
            return allowed.matcher(change.getControlNewText()).matches() ? change : null;
        }));
    }

    /** A non-negative number with up to {@code maxIntDigits} digits and {@code maxDecimals} decimals. */
    public static void decimal(TextInputControl field, int maxIntDigits, int maxDecimals) {
        Pattern allowed = Pattern.compile("\\d{0," + maxIntDigits + "}(\\.\\d{0," + maxDecimals + "})?");
        field.setTextFormatter(new TextFormatter<String>(change -> {
            change.setText(toAsciiDigits(change.getText()));
            return allowed.matcher(change.getControlNewText()).matches() ? change : null;
        }));
    }

    /** Replaces Bangla digits with 0-9; other characters are kept. */
    public static String toAsciiDigits(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(c >= BANGLA_ZERO && c <= BANGLA_ZERO + 9 ? (char) ('0' + (c - BANGLA_ZERO)) : c);
        }
        return sb.toString();
    }
}
