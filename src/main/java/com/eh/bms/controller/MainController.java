package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.model.UserRole;
import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.UnsavedWork;
import com.eh.bms.util.WindowManager;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Main layout: sidebar buttons (only modules the user may view), each loading its own view. */
public class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());
    private static final String ACTIVE = "active";

    /** One sidebar entry: its module, menu text key and view FXML. */
    private record Page(AppModule module, String titleKey, String fxml) {
    }

    private static final List<Page> PAGES = List.of(
            new Page(AppModule.DASHBOARD, "menu.dashboard", "/fxml/views/dashboard.fxml"),
            new Page(AppModule.SALE, "menu.sales", "/fxml/views/sales.fxml"),
            new Page(AppModule.PURCHASE, "menu.purchases", "/fxml/views/purchases.fxml"),
            new Page(AppModule.PRODUCT, "menu.products", "/fxml/views/products.fxml"),
            new Page(AppModule.STOCK, "menu.stock", "/fxml/views/stock.fxml"),
            new Page(AppModule.PARTY, "menu.parties", "/fxml/views/parties.fxml"),
            new Page(AppModule.ACCOUNT, "menu.accounts", "/fxml/views/accounts.fxml"),
            new Page(AppModule.EXPENSE, "menu.expenses", "/fxml/views/expenses.fxml"),
            new Page(AppModule.REPORT, "menu.reports", "/fxml/views/reports.fxml"),
            new Page(AppModule.USER, "menu.users", "/fxml/views/users.fxml"),
            new Page(AppModule.SETTINGS, "menu.settings", "/fxml/views/settings.fxml"));

    @FXML private Label brandLabel;
    @FXML private VBox menuBox;
    @FXML private Button logoutButton;
    @FXML private Label pageTitleLabel;
    @FXML private Label userLabel;
    @FXML private StackPane contentArea;
    @FXML private StackPane messageBar;
    @FXML private Label timeLabel;
    @FXML private Label dateLabel;
    @FXML private Button bnButton;
    @FXML private Button enButton;

    private final Map<AppModule, Button> buttons = new EnumMap<>(AppModule.class);
    private final AppConfigService configService = new AppConfigService();
    private Page currentPage;
    private Timeline clock;

    /** Views use %key text, so the open view is reloaded in the new language. */
    private final ChangeListener<Locale> localeListener = (obs, old, locale) -> {
        if (currentPage != null) {
            open(currentPage);
        }
    };

    @FXML
    private void initialize() {
        brandLabel.textProperty().bind(I18n.bind("app.title"));
        logoutButton.textProperty().bind(I18n.bind("menu.logout"));

        UserSession session = UserSession.get();
        // Role in the current language, e.g. "Administrator (অ্যাডমিন)"
        userLabel.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> session.getName() + " (" + I18n.get("role." + session.getRole().name()) + ")", I18n.localeProperty()));

        // Only modules the user may view get a button (cashier: no HR, reports, settings, users).
        // The support login is only there to reset passwords, so it sees just the Users page.
        boolean support = session.getRole() == UserRole.SUPER_ADMIN;
        List<Page> visible = PAGES.stream()
                .filter(page -> support ? page.module() == AppModule.USER
                        : session.can(page.module(), UserSession.Action.VIEW))
                .toList();

        for (Page page : visible) {
            Button button = new Button();
            button.textProperty().bind(I18n.bind(page.titleKey()));
            button.getStyleClass().add("sidebar-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(event -> open(page));
            buttons.put(page.module(), button);
            menuBox.getChildren().add(button);
        }

        I18n.localeProperty().addListener(localeListener);
        Notifier.setHost(messageBar);
        startClock();
        markLanguage();

        if (visible.isEmpty()) {
            Label none = new Label(I18n.get("msg.no_permission"));
            none.getStyleClass().add("placeholder-text");
            contentArea.getChildren().setAll(none);
        } else {
            open(visible.get(0));
        }
    }

    private void open(Page page) {
        Parent view;
        try {
            view = WindowManager.load(page.fxml());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not open " + page.fxml(), e);
            Label error = new Label(I18n.get("msg.error"));
            error.getStyleClass().add("error-label");
            view = new StackPane(error);
        }
        contentArea.getChildren().setAll(view);
        currentPage = page;
        pageTitleLabel.textProperty().bind(I18n.bind(page.titleKey()));

        buttons.values().forEach(b -> b.getStyleClass().remove(ACTIVE));
        buttons.get(page.module()).getStyleClass().add(ACTIVE);
    }

    /** Time (next to BN/EN) and today's date (next to logout), every second. Follows the date format and language at once. */
    private void startClock() {
        tick();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();
    }

    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        timeLabel.setText(DateUtil.formatTime(now.toLocalTime()));
        dateLabel.setText(DateUtil.format(now.toLocalDate()));
    }

    @FXML
    private void onBangla() {
        switchLanguage("bn");
    }

    @FXML
    private void onEnglish() {
        switchLanguage("en");
    }

    /** Saves the language in app_config and switches the whole UI at once (the open page reloads). */
    private void switchLanguage(String code) {
        if (code.equals(I18n.getLocale().getLanguage())) {
            return;
        }
        // The open page reloads in the new language: offer to save what was typed first
        if (!UnsavedWork.resolve()) {
            return;
        }
        try {
            configService.set(AppConfigService.DEFAULT_LANGUAGE, code);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not save language", e);
            Notifier.error(I18n.get("msg.error"));
            return;
        }
        I18n.setLanguage(code);
        markLanguage();
        tick();
        Notifier.success(I18n.get("settings.language.saved"));
    }

    private void markLanguage() {
        boolean english = "en".equals(I18n.getLocale().getLanguage());
        enButton.getStyleClass().remove(ACTIVE);
        bnButton.getStyleClass().remove(ACTIVE);
        (english ? enButton : bnButton).getStyleClass().add(ACTIVE);
    }

    @FXML
    private void onLogout() {
        I18n.localeProperty().removeListener(localeListener);
        clock.stop();
        Notifier.setHost(null);
        UserSession.end();
        WindowManager.showLogin();
    }
}
