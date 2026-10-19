package com.eh.bms.controller;

import com.eh.bms.model.BalanceType;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyType;
import com.eh.bms.service.PartyService;
import com.eh.bms.service.PartyService.PartyData;
import com.eh.bms.model.AppModule;
import com.eh.bms.service.UserSession;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PartyText;
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
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Add / edit party popup (10.1). Mobile number is required, 11 digits and unique (red error otherwise).
 * The opening balance ("আগের হিসাব") asks who owes whom only when it is more than 0, and is locked once
 * the party has ledger entries.
 */
public class PartyFormController {

    private static final Logger LOG = Logger.getLogger(PartyFormController.class.getName());
    private static final int NAME_MAX = 150;
    private static final int ADDRESS_MAX = 255;
    private static final int MOBILE_LENGTH = 11;

    @FXML private TextField nameField;
    @FXML private Label nameError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;
    @FXML private TextField phoneField;
    @FXML private Label phoneError;
    @FXML private TextField addressField;
    @FXML private Label addressError;
    @FXML private ComboBox<PartyType> typeBox;
    @FXML private Label typeError;
    @FXML private TextField openingField;
    @FXML private Label openingError;
    @FXML private HBox directionRow;
    @FXML private ComboBox<BalanceType> directionBox;
    @FXML private Label directionError;
    @FXML private Label openingLockedLabel;
    @FXML private Button deleteButton;

    private final PartyService service = new PartyService();
    private FormValidator form;

    /** Null when adding. */
    private Long partyId;
    private boolean saved;

    @FXML
    private void initialize() {
        InputFilters.maxLength(nameField, NAME_MAX);
        InputFilters.maxLength(nameEnField, NAME_MAX);
        InputFilters.digits(phoneField, MOBILE_LENGTH);
        InputFilters.maxLength(addressField, ADDRESS_MAX);
        InputFilters.decimal(openingField, 12, 2);

        typeBox.getItems().setAll(PartyType.values());
        typeBox.setConverter(converter(PartyText::typeLong));
        typeBox.setValue(PartyType.CUSTOMER);
        directionBox.getItems().setAll(BalanceType.values());
        directionBox.setConverter(converter(b -> I18n.get("party.opening.direction." + b.name())));
        directionBox.setValue(BalanceType.RECEIVABLE);

        openingField.textProperty().addListener((obs, old, text) -> showDirection());

        form = new FormValidator()
                .add(nameField, nameError)
                .add(nameEnField, nameEnError)
                .add(phoneField, phoneError)
                .add(addressField, addressError)
                .add(typeBox, typeError)
                .add(openingField, openingError)
                .add(directionBox, directionError);

        openingField.setText("0");
    }

    /** Fills the form for editing; call before showing. Leave it uncalled to add a new party. */
    public void edit(Party party) {
        partyId = party.getId();
        nameField.setText(party.getName());
        nameEnField.setText(party.getNameEn());
        phoneField.setText(party.getPhone());
        addressField.setText(party.getAddress());
        typeBox.setValue(party.getType());
        directionBox.setValue(party.getBalanceType());
        openingField.setText(party.getOpeningBalance().stripTrailingZeros().toPlainString());
        boolean locked = service.hasLedger(partyId);
        openingField.setDisable(locked);
        directionBox.setDisable(locked);
        openingLockedLabel.setVisible(locked);
        openingLockedLabel.setManaged(locked);
        // Delete only a party added by mistake: no history and no earlier balance (and the user may delete)
        boolean deletable = UserSession.isLoggedIn() && UserSession.get().can(AppModule.PARTY, Action.DELETE)
                && service.canDelete(partyId);
        deleteButton.setVisible(deletable);
        deleteButton.setManaged(deletable);
    }

    /** Fills a new party's name and mobile, e.g. from a walk-in customer on the sale screen; type = customer. */
    public void prefill(String name, String phone) {
        nameField.setText(name == null ? "" : name.trim());
        phoneField.setText(phone == null ? "" : phone.trim());
        typeBox.setValue(PartyType.CUSTOMER);
    }

    /** The mobile number as saved (identifies the party just added). */
    public String savedPhone() {
        return text(phoneField);
    }

    /** True if the user saved; the list then reloads. */
    public boolean isSaved() {
        return saved;
    }

    /** "Who owes whom" matters only for an amount above 0. */
    private void showDirection() {
        BigDecimal opening = amount(text(openingField));
        boolean show = opening != null && opening.signum() > 0;
        if (show != directionRow.isVisible()) {
            directionRow.setVisible(show);
            directionRow.setManaged(show);
            WindowManager.fitPopup(directionRow);
        }
    }

    @FXML
    private void onSave() {
        form.clearAll();
        String name = text(nameField);
        String nameEn = text(nameEnField);
        String phone = text(phoneField);
        PartyType type = typeBox.getValue();
        BigDecimal opening = amount(text(openingField));
        BalanceType direction = directionBox.getValue();

        if (name.isEmpty()) {
            form.error(nameField, I18n.get("msg.required_field", I18n.get("party.name_bn")));
        }
        if (nameEn.isEmpty()) {
            form.error(nameEnField, I18n.get("msg.required_field", I18n.get("party.name_en")));
        }
        if (phone.isEmpty()) {
            form.error(phoneField, I18n.get("msg.required_field", I18n.get("party.phone")));
        } else if (phone.length() != MOBILE_LENGTH) {
            form.error(phoneField, I18n.get("msg.invalid_mobile"));
        }
        if (type == null) {
            form.error(typeBox, I18n.get("msg.required_field", I18n.get("party.type")));
        }
        if (opening == null) {
            form.error(openingField, I18n.get("msg.required_field", I18n.get("party.opening")));
        } else if (opening.signum() > 0 && direction == null) {
            form.error(directionBox, I18n.get("msg.required_field", I18n.get("party.opening.direction")));
        }
        if (form.hasErrors()) {
            form.focusFirstError();
            return;
        }
        if (!Dialogs.confirmSave()) {
            return;
        }

        try {
            // 0 has no direction; store it as receivable
            BalanceType balanceType = opening.signum() == 0 ? BalanceType.RECEIVABLE : direction;
            PartyService.SaveResult result = service.save(new PartyData(partyId, name, nameEn, phone, text(addressField),
                    type, null, opening, balanceType));
            switch (result) {
                case DUPLICATE_PHONE -> {
                    form.error(phoneField, I18n.get("party.error.duplicate_phone"));
                    form.focusFirstError();
                }
                case SAVED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.saved"));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save party", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onDelete() {
        if (partyId == null || !Dialogs.confirmDelete(I18n.get("party.confirm_delete", text(nameField)))) {
            return;
        }
        try {
            switch (service.delete(partyId)) {
                case DELETED -> {
                    saved = true;
                    close();
                    Notifier.success(I18n.get("msg.deleted"));
                }
                case IN_USE -> Notifier.error(I18n.get("party.error.in_use"));
                case HAS_OPENING -> Notifier.error(I18n.get("party.error.has_opening"));
                case NOT_FOUND -> Notifier.error(I18n.get("msg.error"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not delete party", e);
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

    /** The typed amount, or null when empty. */
    private static BigDecimal amount(String text) {
        if (text.isEmpty() || text.equals(".")) {
            return null;
        }
        return new BigDecimal(text.endsWith(".") ? text + "0" : text);
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    private static <T> StringConverter<T> converter(Function<T, String> text) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : text.apply(value);
            }

            @Override
            public T fromString(String s) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
