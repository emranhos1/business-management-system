package com.eh.bms;

import com.eh.bms.config.AppConfig;
import com.eh.bms.config.DataSeeder;
import com.eh.bms.config.DatabaseConfig;
import com.eh.bms.controller.SplashController;
import com.eh.bms.service.AppConfigService;
import com.eh.bms.util.AppIcons;
import com.eh.bms.util.DateUtil;
import com.eh.bms.util.I18n;
import com.eh.bms.util.WindowManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.logging.Level;
import java.util.logging.Logger;

public class BmsApplication extends Application {

    private static final Logger LOG = Logger.getLogger(BmsApplication.class.getName());
    private static final String SPLASH_FXML = "/fxml/splash.fxml";

    @Override
    public void start(Stage primaryStage) {
        FXMLLoader splashLoader = WindowManager.loader(SPLASH_FXML);
        Parent splashRoot = WindowManager.load(splashLoader, SPLASH_FXML);
        SplashController splash = splashLoader.getController();

        Stage splashStage = new Stage(StageStyle.TRANSPARENT);
        Scene splashScene = WindowManager.scene(splashRoot);
        splashScene.setFill(Color.TRANSPARENT);
        splashStage.setTitle(AppConfig.get().getString("app.name"));
        AppIcons.apply(splashStage);
        splashStage.setScene(splashScene);
        splashStage.centerOnScreen();
        splashStage.show();

        // DB work runs off the JavaFX thread so the splash stays responsive
        Task<String> startup = new Task<>() {
            /** Returns the saved UI language ("bn"/"en"). */
            @Override
            protected String call() {
                updateMessage(I18n.get("splash.connecting"));
                try {
                    DatabaseConfig.init();
                } catch (RuntimeException e) {
                    throw new StartupException("Database connection failed", "db.error.header", "db.error.content", e);
                }
                updateMessage(I18n.get("splash.preparing"));
                try {
                    DataSeeder.run();
                } catch (RuntimeException e) {
                    throw new StartupException("Startup seeding failed", "seed.error.header", "seed.error.content", e);
                }
                DateUtil.reload();
                return new AppConfigService().language();
            }
        };
        splash.statusProperty().bind(startup.messageProperty());

        // Open the next window before closing the splash, so JavaFX never sees zero windows and exits
        startup.setOnSucceeded(event -> {
            try {
                I18n.setLanguage(startup.getValue());
            } catch (IllegalArgumentException e) {
                LOG.warning("Unknown default_language in app_config: " + startup.getValue());
            }
            WindowManager.showLogin();
            splashStage.close();
        });
        startup.setOnFailed(event -> {
            Throwable e = startup.getException();
            if (e instanceof StartupException se) {
                LOG.log(Level.SEVERE, se.getMessage(), se.getCause());
                showStartupError(se.getCause(), se.headerKey, se.contentKey);
            } else {
                LOG.log(Level.SEVERE, "Startup failed", e);
                showStartupError(e, "db.error.header", "db.error.content");
            }
            splashStage.close();
            Platform.exit();
        });

        Thread thread = new Thread(startup, "bms-startup");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        DatabaseConfig.shutdown();
    }

    private void showStartupError(Throwable e, String headerKey, String contentKey) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        AppIcons.apply(alert.getDialogPane().getScene().getWindow());
        alert.setTitle(I18n.get("db.error.title"));
        alert.setHeaderText(I18n.get(headerKey));
        alert.setContentText(I18n.get(contentKey, AppConfig.get().getProfile())
                + "\n\n" + root.getMessage());
        alert.showAndWait();
    }

    /** A startup step failure, with the i18n keys for its error dialog. */
    private static final class StartupException extends RuntimeException {
        private final String headerKey;
        private final String contentKey;

        StartupException(String message, String headerKey, String contentKey, Throwable cause) {
            super(message, cause);
            this.headerKey = headerKey;
            this.contentKey = contentKey;
        }
    }
}
