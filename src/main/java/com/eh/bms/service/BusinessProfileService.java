package com.eh.bms.service;

import com.eh.bms.model.BusinessProfile;
import com.eh.bms.repository.BusinessProfileRepository;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.StringJoiner;

/** Reads and saves the single {@code business_profile} row. */
public class BusinessProfileService {

    private final BusinessProfileRepository repository = new BusinessProfileRepository();
    private final AuditService auditService = new AuditService();

    /** Values from the edit form. Blank optional fields are saved as null. */
    public record ProfileData(String name, String address, String phone, String email,
                              String currencySymbol, String currencySymbolEn, BigDecimal vatPercentage,
                              int financialYearStart, String logoPath) {
    }

    public BusinessProfile get() {
        return TransactionHelper.inTransaction(repository::get);
    }

    public void save(ProfileData data) {
        TransactionHelper.runInTransaction(session -> {
            BusinessProfile p = repository.get(session);
            String old = describe(p);

            p.setName(data.name().trim());
            p.setAddress(blankToNull(data.address()));
            p.setPhone(blankToNull(data.phone()));
            p.setEmail(blankToNull(data.email()));
            p.setCurrencySymbol(data.currencySymbol().trim());
            p.setCurrencySymbolEn(data.currencySymbolEn().trim());
            p.setVatPercentage(data.vatPercentage());
            p.setFinancialYearStart(data.financialYearStart());
            p.setLogoPath(blankToNull(data.logoPath()));

            String now = describe(p);
            if (!Objects.equals(old, now)) {
                auditService.updated(session, "BUSINESS_PROFILE", p.getId(), old, now);
            }
        });
    }

    private static String describe(BusinessProfile p) {
        return new StringJoiner(", ")
                .add("name=" + p.getName())
                .add("address=" + p.getAddress())
                .add("phone=" + p.getPhone())
                .add("email=" + p.getEmail())
                .add("currency=" + p.getCurrencySymbol())
                .add("currencyEn=" + p.getCurrencySymbolEn())
                .add("vat=" + (p.getVatPercentage() == null ? null : p.getVatPercentage().stripTrailingZeros().toPlainString()))
                .add("fyStart=" + p.getFinancialYearStart())
                .add("logo=" + p.getLogoPath())
                .toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
