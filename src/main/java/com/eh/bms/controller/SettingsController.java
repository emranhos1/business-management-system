package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.BusinessProfile;
import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.BusinessProfileService;
import com.eh.bms.service.BusinessProfileService.ProfileData;
import com.eh.bms.service.UserSession.Action;
import com.eh.bms.util.AppPaths;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.Dialogs;
import com.eh.bms.util.FormValidator;
import com.eh.bms.util.I18n;
import com.eh.bms.util.InputFilters;
import com.eh.bms.util.MoneyUtil;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.PermissionUtil;
import com.eh.bms.util.TimePicker;
import com.eh.bms.util.UnsavedWork;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.FlowPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Settings: business profile, app settings and the storage folder.
 * Every save: show all field errors → ask for confirmation → save → message in the top bar.
 */
public class SettingsController {

    private static final Logger LOG = Logger.getLogger(SettingsController.class.getName());
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final BigDecimal VAT_MAX = new BigDecimal("100");
    /** Mobile number: exactly 11 digits (any start). */
    private static final Pattern MOBILE = Pattern.compile("^\\d{11}$");
    private static final int MOBILE_LENGTH = 11;
    private static final int ITEMS_MIN = 5;
    private static final int ITEMS_MAX = 200;
    private static final int LOW_STOCK_MAX = 1_000_000;
    private static final String LOGO_PREFIX = "logo-";
    /** Weekly off day ticks in the order shown: Saturday first, as the week starts in Bangladesh. */
    private static final List<DayOfWeek> WEEK = List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    @FXML private Region root;

    @FXML private TextField nameField;
    @FXML private Label nameError;
    @FXML private TextArea addressField;
    @FXML private TextField phoneField;
    @FXML private Label phoneError;
    @FXML private TextField emailField;
    @FXML private Label emailError;
    @FXML private TextField currencyField;
    @FXML private Label currencyError;
    @FXML private TextField currencyEnField;
    @FXML private Label currencyEnError;
    @FXML private TextField vatField;
    @FXML private Label vatError;
    @FXML private ComboBox<Integer> fyStartBox;
    @FXML private ImageView logoView;
    @FXML private Label noLogoLabel;
    @FXML private Button removeLogoButton;
    @FXML private Button saveProfileButton;

    @FXML private ComboBox<String> dateFormatBox;
    @FXML private Label dateExampleLabel;
    @FXML private ComboBox<String> timeFormatBox;
    @FXML private Label timeExampleLabel;
    @FXML private TextField itemsPerPageField;
    @FXML private Label itemsPerPageError;
    @FXML private TextField lowStockField;
    @FXML private Label lowStockError;
    @FXML private CheckBox autoBackupBox;
    @FXML private FlowPane weeklyOffPane;
    @FXML private TimePicker workStartPicker;
    @FXML private TimePicker workEndPicker;
    @FXML private Label workTimeError;
    @FXML private CheckBox paidLeaveBox;
    @FXML private TextField storageField;
    @FXML private Label storageError;
    @FXML private Button saveAppButton;

    private final BusinessProfileService profileService = new BusinessProfileService();
    private final AppConfigService configService = new AppConfigService();

    private FormValidator profileForm;
    private FormValidator appForm;
    /** One tick per weekday for the weekly off days. */
    private final Map<DayOfWeek, CheckBox> offDayBoxes = new EnumMap<>(DayOfWeek.class);

    /** Logo as stored in the DB (relative to the storage folder); may differ from the DB until Save. */
    private String logoPath;
    /** Logo file chosen but not yet saved; copied into the storage folder only on Save. */
    private Path pendingLogo;
    /** Form contents as last loaded or saved; a difference means unsaved changes. */
    private String profileSnapshot = "";
    private String appSnapshot = "";
    /** Storage folder in use, and the one shown (chosen but not yet saved). */
    private Path currentStorage;
    private Path chosenStorage;

    @FXML
    private void initialize() {
        PermissionUtil.require(saveProfileButton, AppModule.SETTINGS, Action.EDIT);
        PermissionUtil.require(saveAppButton, AppModule.SETTINGS, Action.EDIT);

        InputFilters.maxLength(nameField, 150);
        InputFilters.maxLength(addressField, 255);
        InputFilters.digits(phoneField, MOBILE_LENGTH);
        InputFilters.maxLength(emailField, 100);
        InputFilters.maxLength(currencyField, 10);
        InputFilters.maxLength(currencyEnField, 10);
        InputFilters.decimal(vatField, 3, 2);
        InputFilters.digits(itemsPerPageField, 3);
        InputFilters.digits(lowStockField, 7);

        profileForm = new FormValidator()
                .add(nameField, nameError)
                .add(phoneField, phoneError)
                .add(emailField, emailError)
                .add(currencyField, currencyError)
                .add(currencyEnField, currencyEnError)
                .add(vatField, vatError);
        appForm = new FormValidator()
                .add(itemsPerPageField, itemsPerPageError)
                .add(lowStockField, lowStockError)
                .add(workEndPicker, workTimeError)
                .add(storageField, storageError);
        workStartPicker.setPrompt(I18n.get("time.pick"));
        workEndPicker.setPrompt(I18n.get("time.pick"));
        workStartPicker.setStartTime(() -> AppConfigService.WORK_START_DEFAULT);
        workEndPicker.setStartTime(() -> AppConfigService.WORK_END_DEFAULT);

        fyStartBox.getItems().setAll(IntStream.rangeClosed(1, 12).boxed().toList());
        fyStartBox.setConverter(converter(m -> Month.of(m).getDisplayName(TextStyle.FULL, I18n.getLocale())));

        dateFormatBox.getItems().setAll(DateUtil.PATTERNS);
        dateFormatBox.valueProperty().addListener((obs, old, pattern) -> showDateExample(pattern));
        timeFormatBox.getItems().setAll(DateUtil.TIME_PATTERNS);
        timeFormatBox.setConverter(converter(p -> I18n.get(DateUtil.TIME_24.equals(p) ? "settings.time.24" : "settings.time.12")));
        timeFormatBox.valueProperty().addListener((obs, old, pattern) -> showTimeExample(pattern));
        for (DayOfWeek day : WEEK) {
            CheckBox box = new CheckBox(day.getDisplayName(TextStyle.FULL, I18n.getLocale()));
            offDayBoxes.put(day, box);
            weeklyOffPane.getChildren().add(box);
        }

        loadProfile();
        loadAppConfig();
        profileSnapshot = profileState();
        appSnapshot = appState();
        // Before the language switches (the page reloads), offer to save what was changed
        UnsavedWork.register(root,
                () -> !profileState().equals(profileSnapshot) || !appState().equals(appSnapshot),
                () -> (profileState().equals(profileSnapshot) || saveProfile())
                        && (appState().equals(appSnapshot) || saveApp()));
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
            currencyEnField.setText(MoneyUtil.englishOf(p));
            vatField.setText(p.getVatPercentage() == null ? "0" : p.getVatPercentage().stripTrailingZeros().toPlainString());
            fyStartBox.setValue(p.getFinancialYearStart());
            logoPath = p.getLogoPath();
            showLogo();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load business profile", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onSaveProfile() {
        saveProfile();
    }

    /** True if the profile was saved (false: errors shown, the user said No, or it failed). */
    private boolean saveProfile() {
        profileForm.clearAll();
        String name = text(nameField);
        String phone = text(phoneField);
        String email = text(emailField);
        String currency = text(currencyField);
        String currencyEn = text(currencyEnField);
        String vatText = text(vatField);

        if (name.isEmpty()) {
            profileForm.error(nameField, I18n.get("msg.required_field", I18n.get("settings.business.name")));
        }
        if (phone.isEmpty()) {
            profileForm.error(phoneField, I18n.get("msg.required_field", I18n.get("settings.business.mobile")));
        } else if (!MOBILE.matcher(phone).matches()) {
            profileForm.error(phoneField, I18n.get("msg.invalid_mobile"));
        }
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            profileForm.error(emailField, I18n.get("msg.invalid_email"));
        }
        if (currency.isEmpty()) {
            profileForm.error(currencyField, I18n.get("msg.required_field", I18n.get("settings.business.currency")));
        }
        if (currencyEn.isEmpty()) {
            profileForm.error(currencyEnField, I18n.get("msg.required_field", I18n.get("settings.business.currency_en")));
        }
        BigDecimal vat = null;
        if (vatText.isEmpty()) {
            profileForm.error(vatField, I18n.get("msg.required_field", I18n.get("settings.business.vat")));
        } else {
            vat = new BigDecimal(vatText.endsWith(".") ? vatText + "0" : vatText);
            if (vat.compareTo(VAT_MAX) > 0) {
                profileForm.error(vatField, I18n.get("msg.invalid_range", I18n.get("settings.business.vat"), 0, 100));
            }
        }
        if (profileForm.hasErrors()) {
            profileForm.focusFirstError();
            Notifier.error(I18n.get("msg.form_has_errors"));
            return false;
        }
        if (!Dialogs.confirmSave()) {
            return false;
        }

        int fyStart = fyStartBox.getValue() == null ? 7 : fyStartBox.getValue();
        String savedLogo = logoPath;
        // File to copy into the storage folder: a newly chosen logo, or an old one saved with a full path
        Path source = pendingLogo != null ? pendingLogo
                : logoPath != null && Path.of(logoPath).isAbsolute() && Files.isRegularFile(Path.of(logoPath))
                ? Path.of(logoPath) : null;
        String newLogo = source == null ? logoPath : newLogoName(source);
        ProfileData data = new ProfileData(name, addressField.getText(), phone, email,
                currency, currencyEn, vat, fyStart, newLogo);
        try {
            // 1) save the profile (with the logo's new location) in the DB
            profileService.save(data);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save business profile", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
        if (source != null) {
            // 2) only after the DB save, copy the logo into the storage folder
            try {
                Files.copy(source, AppPaths.resolve(newLogo), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.SEVERE, "Could not copy logo; restoring the old logo in the DB", e);
                restoreLogo(data, savedLogo);
                Notifier.error(I18n.get("msg.error"));
                return false;
            }
        }
        logoPath = newLogo;
        pendingLogo = null;
        deleteUnusedLogos();
        showLogo();
        MoneyUtil.reload(); // currency symbol may have changed
        Notifier.success(I18n.get("msg.saved"));
        profileSnapshot = profileState();
        return true;
    }

    /** Puts the previous logo back in the DB when the new file could not be copied. */
    private void restoreLogo(ProfileData data, String oldLogo) {
        try {
            profileService.save(new ProfileData(data.name(), data.address(), data.phone(), data.email(),
                    data.currencySymbol(), data.currencySymbolEn(), data.vatPercentage(),
                    data.financialYearStart(), oldLogo));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not restore the old logo", e);
        }
    }

    /** Only remembers the chosen file and shows it; nothing is copied until Save. */
    @FXML
    private void onChooseLogo() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("settings.logo.choose"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.get("settings.logo.files"), "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file != null) {
            pendingLogo = file.toPath();
            showLogo();
        }
    }

    /** Takes effect on Save (the DB is updated then, and the old file removed). */
    @FXML
    private void onRemoveLogo() {
        pendingLogo = null;
        logoPath = null;
        showLogo();
    }

    /** Shows the chosen (not yet saved) logo, else the saved one. */
    private void showLogo() {
        Path file = pendingLogo != null ? pendingLogo : logoPath == null ? null : AppPaths.resolve(logoPath);
        Image image = null;
        if (file != null && Files.isRegularFile(file)) {
            image = new Image(file.toUri().toString(), 160, 160, true, true);
            if (image.isError()) {
                image = null;
            }
        }
        logoView.setImage(image);
        noLogoLabel.setVisible(image == null);
        removeLogoButton.setDisable(pendingLogo == null && logoPath == null);
    }

    /** Stored path for a new logo copy: {@code images/logo-<time>.<ext>}. */
    private static String newLogoName(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        // A file with no extension (the chooser lets one through) is still an image: keep it as .png
        String ext = dot < 0 ? ".png" : name.substring(dot).toLowerCase(Locale.ROOT);
        return AppPaths.toStored(AppPaths.imagesDir().resolve(LOGO_PREFIX + System.currentTimeMillis() + ext));
    }

    /** Removes old logo copies (storage images folder and the old app-data folder), keeping the saved one. */
    private void deleteUnusedLogos() {
        Path keep = logoPath == null ? null : AppPaths.resolve(logoPath).toAbsolutePath();
        for (Path dir : new Path[]{AppPaths.imagesDir(), AppPaths.dataDir()}) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, LOGO_PREFIX + "*")) {
                for (Path f : files) {
                    if (!f.toAbsolutePath().equals(keep)) {
                        Files.deleteIfExists(f);
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not clean old logos in " + dir, e);
            }
        }
    }

    // ---- App settings (6.2) and storage folder (6.7) ----

    private void loadAppConfig() {
        try {
            String pattern = configService.getString(AppConfigService.DATE_FORMAT, DateUtil.DEFAULT_PATTERN);
            if (!dateFormatBox.getItems().contains(pattern) && DateUtil.isValid(pattern)) {
                dateFormatBox.getItems().add(pattern);
            }
            dateFormatBox.setValue(pattern);
            String time = configService.getString(AppConfigService.TIME_FORMAT, DateUtil.DEFAULT_TIME_PATTERN);
            timeFormatBox.setValue(DateUtil.TIME_PATTERNS.contains(time) ? time : DateUtil.DEFAULT_TIME_PATTERN);
            itemsPerPageField.setText(String.valueOf(configService.getInt(AppConfigService.ITEMS_PER_PAGE, 20)));
            lowStockField.setText(String.valueOf(configService.getInt(AppConfigService.LOW_STOCK_DEFAULT, 10)));
            autoBackupBox.setSelected(configService.getBoolean(AppConfigService.AUTO_BACKUP, false));
            Set<DayOfWeek> offDays = configService.weeklyOffDays();
            offDayBoxes.forEach((day, box) -> box.setSelected(offDays.contains(day)));
            workStartPicker.setValue(configService.workStart());
            workEndPicker.setValue(configService.workEnd());
            paidLeaveBox.setSelected(configService.paidLeave());
            currentStorage = AppPaths.storageDir().toAbsolutePath();
            chosenStorage = currentStorage;
            storageField.setText(currentStorage.toString());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load app settings", e);
            Notifier.error(I18n.get("msg.error"));
        }
    }

    @FXML
    private void onChooseStorage() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("settings.storage.choose"));
        if (chosenStorage != null && Files.isDirectory(chosenStorage)) {
            chooser.setInitialDirectory(chosenStorage.toFile());
        }
        File dir = chooser.showDialog(root.getScene().getWindow());
        if (dir != null) {
            chosenStorage = dir.toPath().toAbsolutePath();
            storageField.setText(chosenStorage.toString());
        }
    }

    @FXML
    private void onSaveApp() {
        saveApp();
    }

    /** True if the app settings were saved. */
    private boolean saveApp() {
        appForm.clearAll();
        Integer items = intInRange(itemsPerPageField, "settings.items_per_page", ITEMS_MIN, ITEMS_MAX);
        Integer lowStock = intInRange(lowStockField, "settings.low_stock_default", 0, LOW_STOCK_MAX);
        boolean storageChanged = chosenStorage != null && !chosenStorage.equals(currentStorage);
        if (storageChanged && chosenStorage.startsWith(currentStorage)) {
            // Copying a folder into itself would copy it again and again (images/images/...)
            appForm.error(storageField, I18n.get("settings.storage.inside_current"));
        } else if (storageChanged && !AppPaths.isWritable(chosenStorage)) {
            appForm.error(storageField, I18n.get("settings.storage.not_writable"));
        }
        LocalTime workStart = workStartPicker.getValue();
        LocalTime workEnd = workEndPicker.getValue();
        if (workStart == null || workEnd == null) {
            appForm.error(workEndPicker, I18n.get("msg.required_field", I18n.get("settings.work_time")));
        } else if (!workEnd.isAfter(workStart)) {
            appForm.error(workEndPicker, I18n.get("settings.work_time.order"));
        }
        if (appForm.hasErrors()) {
            appForm.focusFirstError();
            Notifier.error(I18n.get("msg.form_has_errors"));
            return false;
        }
        if (!Dialogs.confirmSave()) {
            return false;
        }

        String pattern = dateFormatBox.getValue() == null ? DateUtil.DEFAULT_PATTERN : dateFormatBox.getValue();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(AppConfigService.DATE_FORMAT, pattern);
        String timePattern = timeFormatBox.getValue() == null ? DateUtil.DEFAULT_TIME_PATTERN : timeFormatBox.getValue();
        values.put(AppConfigService.TIME_FORMAT, timePattern);
        values.put(AppConfigService.ITEMS_PER_PAGE, String.valueOf(items));
        values.put(AppConfigService.LOW_STOCK_DEFAULT, String.valueOf(lowStock));
        values.put(AppConfigService.AUTO_BACKUP, String.valueOf(autoBackupBox.isSelected()));
        // Same text as AppConfigService.setWeeklyOffDays, saved (and audited) with the other settings
        values.put(AppConfigService.WEEKLY_OFF_DAYS, AppConfigService.formatOffDays(selectedOffDays()));
        values.put(AppConfigService.WORK_START_TIME, AppConfigService.formatTime(workStart));
        values.put(AppConfigService.WORK_END_TIME, AppConfigService.formatTime(workEnd));
        values.put(AppConfigService.PAID_LEAVE, String.valueOf(paidLeaveBox.isSelected()));
        try {
            if (storageChanged) {
                // Copy first: if it fails, nothing is saved and the old folder stays in use
                AppPaths.copyStorage(currentStorage, chosenStorage);
                values.put(AppConfigService.STORAGE_PATH, chosenStorage.toString());
            }
            configService.setAll(values);
            DateUtil.use(pattern, timePattern);
            currentStorage = chosenStorage;
            Notifier.success(I18n.get(storageChanged ? "settings.storage.moved" : "msg.saved"));
            appSnapshot = appState();
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save app settings", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
    }

    /** Everything the business profile form shows, to tell whether it was changed since the last load/save. */
    private String profileState() {
        return String.join("\u0001", text(nameField), text(addressField), text(phoneField), text(emailField),
                text(currencyField), text(currencyEnField), text(vatField), String.valueOf(fyStartBox.getValue()),
                String.valueOf(pendingLogo), String.valueOf(logoPath));
    }

    private String appState() {
        return String.join("\u0001", String.valueOf(dateFormatBox.getValue()), String.valueOf(timeFormatBox.getValue()),
                text(itemsPerPageField),
                text(lowStockField), String.valueOf(autoBackupBox.isSelected()), String.valueOf(chosenStorage),
                AppConfigService.formatOffDays(selectedOffDays()), String.valueOf(workStartPicker.getValue()),
                String.valueOf(workEndPicker.getValue()), String.valueOf(paidLeaveBox.isSelected()));
    }

    /** The ticked weekly off days (none = no weekly off). */
    private Set<DayOfWeek> selectedOffDays() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        offDayBoxes.forEach((day, box) -> {
            if (box.isSelected()) {
                days.add(day);
            }
        });
        return days;
    }

    /** Whole number from a digits-only field; shows the field error and returns null if empty or out of range. */
    private Integer intInRange(TextField field, String labelKey, int min, int max) {
        String value = text(field);
        if (value.isEmpty()) {
            appForm.error(field, I18n.get("msg.required_field", I18n.get(labelKey)));
            return null;
        }
        int n = Integer.parseInt(value);
        if (n < min || n > max) {
            appForm.error(field, I18n.get("msg.invalid_range", I18n.get(labelKey), min, max));
            return null;
        }
        return n;
    }

    private void showDateExample(String pattern) {
        if (pattern == null || !DateUtil.isValid(pattern)) {
            dateExampleLabel.setText("");
            return;
        }
        String example = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(LocalDate.now());
        dateExampleLabel.setText(I18n.get("settings.date_example", I18n.digits(example)));
    }

    private void showTimeExample(String pattern) {
        String example = pattern == null ? ""
                : DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(LocalTime.now());
        timeExampleLabel.setText(pattern == null ? "" : I18n.get("settings.date_example", I18n.digits(example)));
    }

    // ---- helpers ----

    private static String text(TextInputControl field) {
        return field.getText() == null ? "" : field.getText().trim();
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
}
