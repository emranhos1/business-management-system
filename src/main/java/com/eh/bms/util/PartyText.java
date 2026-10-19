package com.eh.bms.util;

import com.eh.bms.model.Party;
import com.eh.bms.model.PartyTradeType;
import com.eh.bms.model.PartyType;

import java.math.BigDecimal;

/**
 * Party words for screens, in plain language (no debit, credit or minus sign):
 * a balance shows as "৳X পাবো" (green, the party owes us) or "৳X দেবো" (red, we owe the party).
 */
public final class PartyText {

    /** Style classes (main.css) for পাবো and দেবো. */
    public static final String RECEIVE_STYLE = "money-in";
    public static final String PAY_STYLE = "money-out";

    private PartyText() {
    }

    /** "৳X পাবো", "৳X দেবো" or "কোনো বাকি নেই". {@code balance}: + party owes us, - we owe the party. */
    public static String balance(BigDecimal balance) {
        if (balance == null) {
            return "";
        }
        if (balance.signum() == 0) {
            return I18n.get("party.balance.clear");
        }
        return I18n.get(balance.signum() > 0 ? "party.balance.receive" : "party.balance.pay",
                MoneyUtil.format(balance.abs()));
    }

    /** Green for পাবো, red for দেবো, none when nothing is due. */
    public static String balanceStyle(BigDecimal balance) {
        if (balance == null || balance.signum() == 0) {
            return null;
        }
        return balance.signum() > 0 ? RECEIVE_STYLE : PAY_STYLE;
    }

    /** Short type for tables, e.g. "ক্রেতা". */
    public static String type(PartyType type) {
        return type == null ? "—" : I18n.get("party.type.short." + type.name());
    }

    /** Type with what it means, for the form's list. */
    public static String typeLong(PartyType type) {
        return type == null ? "" : I18n.get("party.type." + type.name());
    }

    public static String trade(PartyTradeType trade) {
        return trade == null ? "—" : I18n.get("party.trade." + trade.name());
    }

    /** Name in the current language (the Bangla one when there is no English name). */
    public static String name(Party p) {
        return I18n.pick(p.getName(), p.getNameEn());
    }

    /** "নাম (মোবাইল)", e.g. for window titles. */
    public static String nameWithPhone(Party p) {
        return name(p) + " (" + p.getPhone() + ")";
    }
}
