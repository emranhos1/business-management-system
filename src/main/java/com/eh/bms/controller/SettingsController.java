package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.BusinessProfile;
import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.BusinessProfileService;
import com.eh.bms.service.BusinessProfileService.ProfileData;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.AppPaths;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.PermissionUtil;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Settings: business profile, app config and language. */
public class SettingsController {

    private static final Logger LOG = Logger.getLogger(SettingsController.class.getName());
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final BigDecimal VAT_MAX = new BigDecimal("100");
    private static final int ITEMS_MIN = 5;
    private static final int ITEMS_MAX = 200;
    private static final int LOW_STOCK_MAX = 1_000_000;

    /** The page reloads when the language changes; these keep the open tab and the saved message. */
    private static int lastTab;
    private static boolean languageJustSaved;

    @FXML private TabPane tabPane;

    @FXML private TextField nameField;
    @FXML private TextArea addressField;
    @FXML private TextField phoneField;
    @FXML private TextField emailField;
    @FXML private TextField currencyField;
    @FXML private TextField vatField;
    @FXML private ComboBox<Integer> fyStartBox;
    @FXML private ImageView logoView;
    @FXML private Label noLogoLabel;
    @FXML private Button removeLogoButton;
    @FXML private Button saveProfileButton;
    @FXML private Label profileStatus;

    @FXML private ComboBox<String> languageBox;
    @FXML private ComboBox<String> dateFormatBox;
    @FXML private Label dateExampleLabel;
    @FXML private Spinner<Integer> itemsPerPageSpinner;
    @FXML private Spinner<Integer> lowStockSpinner;
    @FXML private CheckBox autoBackupBox;
    @FXML private Button saveAppButton;
    @FXML private Label appStatus;

    private final BusinessProfileService profileService = new BusinessProfileService();
    private final AppConfigService configService = new AppConfigService();

    /** Logo path to save; may differ from the DB until Save is pressed. */
    private String logoPath;

    @FXML
    private void initialize() {
        PermissionUtil.require(saveProfileButton, AppModule.SETTINGS, Action.EDIT);
        PermissionUtil.require(saveAppButton, AppModule.SETTINGS, Action.EDIT);

        limit(nameField, 150);
        limit(addressField, 255);
        limit(phoneField, 30);
        limit(emailField, 100);
        limit(currencyField, 10);
        limit(vatField, 6);

        fyStartBox.getItems().setAll(IntStream.rangeClosed(1, 12).boxed().toList());
        fyStartBox.setConverter(converter(m -> Month.of(m).getDisplayName(TextStyle.FULL, I18n.getLocale())));

        languageBox.getItems().setAll("bn", "en");
        languageBox.setConverter(converter(code -> "bn".equals(code) ? "বাংলা" : "English"));

        dateFormatBox.getItems().setAll(DateUtil.PATTERNS);
        dateFormatBox.valueProperty().addListener((obs, old, pattern) -> showDateExample(pattern));

        itemsPerPageSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(ITEMS_MIN, ITEMS_MAX, 20, 5));
        lowStockSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, LOW_STOCK_MAX, 10));

        loadProfile();
        loadAppConfig();

        tabPane.getSelectionModel().select(lastTab);
        tabPane.getSelectionModel().selectedIndexProperty().addListener((obs, old, index) -> lastTab = index.intValue());
        if (languageJustSaved) {
            showStatus(appStatus, I18n.get("settings.language.saved"), false);
            languageJustSaved = false;
        }

        // Added after loading, so only a user change saves and switches the language
        languageBox.valueProperty().addListener((obs, old, code) -> onLanguageChanged(code));
    }

    // ---- Business profile (6.1) ----

    private void loadProfile() {
        try {
            BusinessProfile p = profileService.get();
            nameField.setText(p.getName());
            addressField.setText(p.getAddress());
            phoneField.setText(p.getPhone());
            emailField.setText(p.getEmail());
            currencyField.setText(p.getCurrencySymbol());
            vatField.setText(p.getVatPercentage() == null ? "0" : p.getVatPercentage().stripTrailingZeros().toPlainString());
            fyStartBox.setValue(p.getFinancialYearStart());
            logoPath = p.getLogoPath();
            showLogo();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load business profile", e);
            showStatus(profileStatus, I18n.get("msg.error"), true);
        }
    }

    @FXML
    private void onSaveProfile() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            fail(profileStatus, nameField, I18n.get("msg.required_field", I18n.get("settings.business.name")));
            return;
        }
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            fail(profileStatus, emailField, I18n.get("msg.invalid_email"));
            return;
        }
        String currency = currencyField.getText() == null ? "" : currencyField.getText().trim();
        if (currency.isEmpty()) {
            fail(profileStatus, currencyField, I18n.get("msg.required_field", I18n.get("settings.business.currency")));
            return;
        }
        BigDecimal vat;
        try {
            vat = new BigDecimal(vatField.getText().trim());
        } catch (RuntimeException e) {
            fail(profileStatus, vatField, I18n.get("msg.invalid_number"));
            return;
        }
        if (vat.signum() < 0 || vat.compareTo(VAT_MAX) > 0 || vat.scale() > 2) {
            fail(profileStatus, vatField, I18n.get("msg.invalid_range", I18n.get("settings.business.vat"), 0, 100));
            return;
        }
        int fyStart = fyStartBox.getValue() == null ? 7 : fyStartBox.getValue();

        try {
            profileService.save(new ProfileData(name, addressField.getText(), phoneField.getText(), email,
                    currency, vat, fyStart, logoPath));
            deleteUnusedLogos();
            showStatus(profileStatus, I18n.get("msg.saved"), false);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save business profile", e);
            showStatus(profileStatus, I18n.get("msg.error"), true);
        }
    }

    @FXML
    private void onChooseLogo() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("settings.logo.choose"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.get("settings.logo.files"), "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog(tabPane.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            // Copy into the app folder so moving or deleting the original does not lose the logo
            String name = file.getName();
            String ext = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            Path target = AppPaths.dataDir().resolve("logo-" + System.currentTimeMillis() + ext);
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            logoPath = target.toString();
            showLogo();
            showStatus(profileStatus, "", false);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not copy logo", e);
            showStatus(profileStatus, I18n.get("msg.error"), true);
        }
    }

    @FXML
    private void onRemoveLogo() {
        logoPath = null;
        showLogo();
    }

    private void showLogo() {
        Image image = null;
        if (logoPath != null && Files.isRegularFile(Path.of(logoPath))) {
            image = new Image(Path.of(logoPath).toUri().toString(), 160, 160, true, true);
            if (image.isError()) {
                image = null;
            }
        }
        logoView.setImage(image);
        noLogoLabel.setVisible(image == null);
        removeLogoButton.setDisable(logoPath == null);
    }

    /** Removes old logo copies from the app folder, keeping the saved one. */
    private void deleteUnusedLogos() {
        Path keep = logoPath == null ? null : Path.of(logoPath).toAbsolutePath();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(AppPaths.dataDir(), "logo-*")) {
            for (Path f : files) {
                if (!f.toAbsolutePath().equals(keep)) {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not clean old logos", e);
        }
    }

    // ---- App settings (6.2) and language (6.3) ----

    private void loadAppConfig() {
        try {
            languageBox.setValue("en".equals(configService.language()) ? "en" : "bn");
            String pattern = configService.getString(AppConfigService.DATE_FORMAT, DateUtil.DEFAULT_PATTERN);
            if (!dateFormatBox.getItems().contains(pattern) && DateUtil.isValid(pattern)) {
                dateFormatBox.getItems().add(pattern);
            }
            dateFormatBox.setValue(pattern);
            itemsPerPageSpinner.getValueFactory().setValue(
                    clamp(configService.getInt(AppConfigService.ITEMS_PER_PAGE, 20), ITEMS_MIN, ITEMS_MAX));
            lowStockSpinner.getValueFactory().setValue(
                    clamp(configService.getInt(AppConfigService.LOW_STOCK_DEFAULT, 10), 0, LOW_STOCK_MAX));
            autoBackupBox.setSelected(configService.getBoolean(AppConfigService.AUTO_BACKUP, false));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load app settings", e);
            showStatus(appStatus, I18n.get("msg.error"), true);
        }
    }

    @FXML
    private void onSaveApp() {
        Integer items = commit(itemsPerPageSpinner);
        if (items == null) {
            fail(appStatus, itemsPerPageSpinner.getEditor(),
                    I18n.get("msg.invalid_range", I18n.get("settings.items_per_page"), ITEMS_MIN, ITEMS_MAX));
            return;
        }
        Integer lowStock = commit(lowStockSpinner);
        if (lowStock == null) {
            fail(appStatus, lowStockSpinner.getEditor(),
                    I18n.get("msg.invalid_range", I18n.get("settings.low_stock_default"), 0, LOW_STOCK_MAX));
            return;
        }
        String pattern = dateFormatBox.getValue() == null ? DateUtil.DEFAULT_PATTERN : dateFormatBox.getValue();

        Map<String, String> values = new LinkedHashMap<>();
        values.put(AppConfigService.DATE_FORMAT, pattern);
        values.put(AppConfigService.ITEMS_PER_PAGE, String.valueOf(items));
        values.put(AppConfigService.LOW_STOCK_DEFAULT, String.valueOf(lowStock));
        values.put(AppConfigService.AUTO_BACKUP, String.valueOf(autoBackupBox.isSelected()));
        try {
            configService.setAll(values);
            DateUtil.use(pattern);
            showStatus(appStatus, I18n.get("msg.saved"), false);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save app settings", e);
            showStatus(appStatus, I18n.get("msg.error"), true);
        }
    }

    private void onLanguageChanged(String code) {
        if (code == null || code.equals(I18n.getLocale().getLanguage())) {
            return;
        }
        try {
            configService.set(AppConfigService.DEFAULT_LANGUAGE, code);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save language", e);
            showStatus(appStatus, I18n.get("msg.error"), true);
            return;
        }
        // The main layout reloads this page in the new language; the new page shows the message
        languageJustSaved = true;
        I18n.setLanguage(code);
    }

    private void showDateExample(String pattern) {
        if (pattern == null || !DateUtil.isValid(pattern)) {
            dateExampleLabel.setText("");
            return;
        }
        String example = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(LocalDate.now());
        dateExampleLabel.setText(I18n.get("settings.date_example", example));
    }

    // ---- helpers ----

    private static void limit(TextInputControl field, int maxLength) {
        field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= maxLength ? change : null));
    }

    /** Commits typed spinner text; null if it is not a number in range. */
    private static Integer commit(Spinner<Integer> spinner) {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                (SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory();
        try {
            int value = Integer.parseInt(spinner.getEditor().getText().trim());
            if (value < factory.getMin() || value > factory.getMax()) {
                return null;
            }
            factory.setValue(value);
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static <T> StringConverter<T> converter(Function<T, String> toText) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : toText.apply(value);
            }

            @Override
            public T fromString(String text) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static void fail(Label status, Node field, String message) {
        showStatus(status, message, true);
        field.requestFocus();
    }

    private static void showStatus(Label label, String message, boolean error) {
        label.getStyleClass().removeAll("error-label", "success-label");
        label.getStyleClass().add(error ? "error-label" : "success-label");
        label.setText(message);
    }
}
