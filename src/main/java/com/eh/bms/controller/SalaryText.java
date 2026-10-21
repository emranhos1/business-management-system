package com.eh.bms.controller;

import com.eh.bms.model.SalaryType;
import com.eh.bms.model.User;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.PartyText;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;

/** Texts and month pickers shared by the users list, the salary popup and the salary history (N12). */
public final class SalaryText {

    /** Years in the month pickers: this many back from this year. */
    private static final int YEARS_BACK = 5;

    private SalaryText() {
    }

    /** Advance carried from earlier months, or "—". */
    public static String carryText(BigDecimal carryIn) {
        return carryIn == null || carryIn.signum() == 0 ? "—" : MoneyUtil.format(carryIn);
    }

    /** A processed month's due for a table cell: the amount, or "অগ্রিম বাকি ৳X" when paid ahead. */
    public static String dueCell(BigDecimal due, BigDecimal advanceLeft) {
        return advanceLeft != null && advanceLeft.signum() > 0
                ? I18n.get("salary.advance_left", MoneyUtil.format(advanceLeft)) : MoneyUtil.format(due);
    }

    /** "বাকি ৳X", or "অগ্রিম বাকি ৳X" when paid ahead. */
    public static String dueText(BigDecimal due, BigDecimal advanceLeft) {
        return advanceLeft != null && advanceLeft.signum() > 0
                ? I18n.get("salary.advance_left", MoneyUtil.format(advanceLeft))
                : I18n.get("salary.due_text", MoneyUtil.format(due));
    }


    /** Red while something is still due. */
    public static String dueStyle(BigDecimal due) {
        return due != null && due.signum() > 0 ? PartyText.PAY_STYLE : null;
    }

    /** Puts {@code style} (may be null) on the label in place of the due colour it had. */
    public static void setStyle(Label label, String style) {
        label.getStyleClass().remove(PartyText.PAY_STYLE);
        if (style != null) {
            label.getStyleClass().add(style);
        }
    }

    /** "অ্যাডমিন" / "ক্যাশিয়ার" ... */
    public static String roleText(User u) {
        return u.getRole() == null ? "—" : I18n.get("role." + u.getRole().name());
    }

    /** "মাসিক" / "দৈনিক" / "ঘণ্টা ভিত্তিক"; "—" when not set yet. */
    public static String typeText(SalaryType type) {
        return type == null ? "—" : I18n.get("salary.type." + type.name());
    }

    /** Label of the salary amount by type: per month / per day / per hour. */
    public static String baseSalaryKey(SalaryType type) {
        return type == null ? "employee.base_salary" : "employee.base_salary." + type.name();
    }

    /** Salary with its period, e.g. "৳ 500.00 / দিন"; "—" when not set yet. */
    public static String baseText(User u) {
        return u.hasSalary() ? I18n.get("salary.base." + u.getSalaryType().name(), MoneyUtil.format(u.getBaseSalary()))
                : "—";
    }

    /** e.g. "অক্টোবর ২০২৬". */
    public static String monthText(YearMonth month) {
        return month == null ? "" : monthName(month.getMonth()) + " " + I18n.digits(String.valueOf(month.getYear()));
    }

    public static String monthName(Month m) {
        return m.getDisplayName(TextStyle.FULL_STANDALONE, I18n.getLocale());
    }

    /** A count or amount of days / hours without extra zeros, e.g. "২২.৫". */
    public static String number(BigDecimal value) {
        return value == null ? "" : I18n.digits(value.stripTrailingZeros().toPlainString());
    }

    public static void monthConverter(ComboBox<Month> box) {
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Month m) {
                return m == null ? "" : monthName(m);
            }

            @Override
            public Month fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
    }

    public static void yearConverter(ComboBox<Integer> box) {
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer y) {
                return y == null ? "" : I18n.digits(String.valueOf(y));
            }

            @Override
            public Integer fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
    }

    /** This year down to {@link #YEARS_BACK} years ago, plus {@code month}'s year if it is older. */
    public static List<Integer> years(YearMonth month) {
        int now = YearMonth.now().getYear();
        List<Integer> years = new ArrayList<>();
        for (int y = now; y >= Math.min(now - YEARS_BACK, month.getYear()); y--) {
            years.add(y);
        }
        return years;
    }

    /** Salary is on the users page: the admin's alone. */
    public static boolean isAdmin() {
        return UserSession.isLoggedIn() && UserSession.get().isAdmin();
    }
}
