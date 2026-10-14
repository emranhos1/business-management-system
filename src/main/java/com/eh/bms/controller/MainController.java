package com.eh.bms.controller;

import com.eh.bms.model.AppModule;
import com.eh.bms.service.UserSession;
import com.eh.bms.util.I18n;
import com.eh.bms.util.WindowManager;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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
            new Page(AppModule.EMPLOYEE, "menu.employees", "/fxml/views/employees.fxml"),
            new Page(AppModule.REPORT, "menu.reports", "/fxml/views/reports.fxml"),
            new Page(AppModule.USER, "menu.users", "/fxml/views/users.fxml"),
            new Page(AppModule.SETTINGS, "menu.settings", "/fxml/views/settings.fxml"));

    @FXML private Label brandLabel;
    @FXML private VBox menuBox;
    @FXML private Button logoutButton;
    @FXML private Label pageTitleLabel;
    @FXML private Label userLabel;
    @FXML private StackPane contentArea;

    private final Map<AppModule, Button> buttons = new EnumMap<>(AppModule.class);
    private Page currentPage;

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
        userLabel.setText(session.getName() + " (" + session.getRole() + ")");

        // Only modules the user may view get a button (cashier: no HR, reports, settings, users)
        List<Page> visible = PAGES.stream()
                .filter(page -> session.can(page.module(), UserSession.Action.VIEW))
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

    @FXML
    private void onLogout() {
        I18n.localeProperty().removeListener(localeListener);
        UserSession.end();
        WindowManager.showLogin();
    }
}
