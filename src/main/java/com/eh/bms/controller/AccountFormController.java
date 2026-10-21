package com.eh.bms.controller;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AccountType;
import com.eh.bms.model.AppModule;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.service.AccountService;
import com.eh.bms.service.AccountService.AccountData;
import com.eh.bms.service.AccountTypeService;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Add / edit account popup (7.1). The type comes from the user-managed account types.
 * The number row shows only for a type that needs one: bank = digits (max 20), mobile = exactly 11 digits.
 * Opening balance is locked once the account has ledger entries.
 */
public class AccountFormController {

    private static final Logger LOG = Logger.getLogger(AccountFormController.class.getName());
    private static final int BANK_NUMBER_MAX = 20;
    private static final int MOBILE_LENGTH = 11;

    @FXML private TextField nameField;
    @FXML private Label nameError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;
    @FXML private ComboBox<AccountType> typeBox;
    @FXML private Label typeError;
    @FXML private HBox numberRow;
    @FXML private TextField numberField;
    @FXML private Label numberError;
    @FXML private TextField openingField;
    @FXML private Label openingError;
    @FXML private Label openingLockedLabel;
    @FXML private Button deleteButton;

    private final AccountService service = new AccountService();
    private final AccountTypeService typeService = new AccountTypeService();
    private FormValidator form;

    /** Null when adding. */
    private Long accountId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameField, 100);
        InputFilters.maxLength(nameEnField, 100);
        InputFilters.decimal(openingField, 12, 2);

        typeBox.getItems().setAll(typeService.all());
        typeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(AccountType type) {
                return type == null ? "" : typeText(type);
            }

            @Override
            public AccountType fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        // A user change of type clears the number, since the number rule may change with it
        typeBox.valueProperty().addListener((obs, old, type) -> {
            applyRule(type == null ? AccountNumberRule.NONE : type.getNumberRule());
            if (old != null) {
                numberField.clear();
            }
        });

        form = new FormValidator()
                .add(nameField, nameError)
                .add(nameEnField, nameEnError)
                .add(typeBox, typeError)
                .add(numberField, numberError)
                .add(openingField, openingError);

        openingField.setText("0");
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new account. */
    public void edit(Account account) {
        accountId = account.getId();
        nameField.setText(account.getName());
        nameEnField.setText(account.getNameEn());
        if (account.getType() != null) {
            // Select the list's own instance (same id), so the box shows it as chosen
            typeBox.getItems().stream()
                    .filter(t -> t.getId().equals(account.getType().getId()))
                    .findFirst()
                    .ifPresent(typeBox::setValue);
        }
        numberField.setText(account.getAccountNumber());
        // The type's number rule may have changed since: the old number is then refused by the field, so say it
        String oldNumber = account.getAccountNumber();
        if (oldNumber != null && !oldNumber.isBlank() && !oldNumber.equals(numberField.getText())) {
            form.error(numberField, I18n.get("accounts.error.old_number", oldNumber));
        }
        openingField.setText(account.getOpeningBalance().stripTrailingZeros().toPlainString());
        boolean locked = service.hasTransactions(accountId);
        openingField.setDisable(locked);
        openingLockedLabel.setVisible(locked);
        openingLockedLabel.setManaged(locked);
        // Delete only an account added by mistake: no history and opening balance 0 (and the user may delete)
        boolean deletable = UserSession.isLoggedIn() && UserSession.get().can(AppModule.ACCOUNT, Action.DELETE)
                && service.canDelete(accountId);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
    }

    /** True if the user saved; the list then reloads. */
    public boolean isSaved() {
        return saved;
    }

    /** Type name in the current language. */
    public static String typeText(AccountType type) {
        return I18n.pick(type.getNameBn(), type.getNameEn());
    }

    /** Account name with its type in brackets, e.g. "01670932273 (বিকাশ)"; the type is left out when it is the name. */
    public static String nameWithType(Account a) {
        String name = I18n.pick(a.getName(), a.getNameEn());
        if (a.getType() == null) {
            return name;
        }
        String type = typeText(a.getType());
        return type.equalsIgnoreCase(name) ? name : name + " (" + type + ")";
    }

    /** Shows the number row only when the type needs a number, with that type's digit limit. */
    private void applyRule(AccountNumberRule rule) {
        boolean show = rule != AccountNumberRule.NONE;
        numberRow.setVisible(show);
        numberRow.setManaged(show);
        numberField.setPromptText(rule == AccountNumberRule.MOBILE ? "01XXXXXXXXX" : "");
        InputFilters.digits(numberField, rule == AccountNumberRule.MOBILE ? MOBILE_LENGTH : BANK_NUMBER_MAX);
        WindowManager.fitPopup(numberField);
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String nameBn = text(nameField);
        String nameEn = text(nameEnField);
        AccountType type = typeBox.getValue();
        AccountNumberRule rule = type == null ? AccountNumberRule.NONE : type.getNumberRule();
        String number = text(numberField);
        String openingText = text(openingField);

        if (nameBn.isEmpty()) {
            form.error(nameField, I18n.get("msg.required_field", I18n.get("accounts.name_bn")));
        }
        if (nameEn.isEmpty()) {
            form.error(nameEnField, I18n.get("msg.required_field", I18n.get("accounts.name_en")));
        }
        if (type == null) {
            form.error(typeBox, I18n.get("msg.required_field", I18n.get("accounts.type")));
        } else if (rule != AccountNumberRule.NONE && number.isEmpty()) {
            form.error(numberField, I18n.get("msg.required_field", I18n.get("accounts.number")));
        } else if (rule == AccountNumberRule.MOBILE && number.length() != MOBILE_LENGTH) {
            form.error(numberField, I18n.get("msg.invalid_mobile"));
        }
        BigDecimal opening = null;
        if (openingText.isEmpty()) {
            form.error(openingField, I18n.get("msg.required_field", I18n.get("accounts.opening")));
        } else {
            opening = new BigDecimal(openingText.endsWith(".") ? openingText + "0" : openingText);
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            AccountService.SaveResult result = service.save(new AccountData(accountId, nameBn, nameEn, type.getId(),
                    rule == AccountNumberRule.NONE ? null : number, opening));
            switch (result) {
                case DUPLICATE_NAME_BN -> {
                    form.error(nameField, I18n.get("accounts.error.duplicate"));
                    form.focusFirstError();
                }
                case DUPLICATE_NAME_EN -> {
                    form.error(nameEnField, I18n.get("accounts.error.duplicate"));
                    form.focusFirstError();
                }
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save account", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (accountId == null || !Dialogs.confirmDelete(I18n.get("accounts.confirm_delete",
                I18n.pick(text(nameField), text(nameEnField))))) {
            return;
        }
        try {
            switch (service.delete(accountId)) {
                case DELETED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                case IN_USE -> Notifier.error(I18n.get("accounts.error.in_use"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete account", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        ((Stage) nameField.getScene().getWindow()).close();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
