package com.eh.bms;

import com.eh.bms.config.AppConfig;
import com.eh.bms.config.DatabaseConfig;
import com.eh.bms.util.I18n;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.net.URL;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BmsApplication extends Application {

    private static final Logger LOG = Logger.getLogger(BmsApplication.class.getName());
    private static final String MAIN_CSS = "/css/main.css";

    @Override
    public void start(Stage stage) {
        try {
            DatabaseConfig.init();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Database connection failed", e);
            showDatabaseError(e);
            Platform.exit();
            return;
        }

        StackPane root = new StackPane();
        Scene scene = new Scene(root, 1024, 680);

        URL css = Objects.requireNonNull(getClass().getResource(MAIN_CSS), "Missing " + MAIN_CSS);
        scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle(AppConfig.get().getString("app.name"));
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        DatabaseConfig.shutdown();
    }

    private void showDatabaseError(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("db.error.title"));
        alert.setHeaderText(I18n.get("db.error.header"));
        alert.setContentText(I18n.get("db.error.content", AppConfig.get().getProfile())
                + "\n\n" + root.getMessage());
        alert.showAndWait();
    }
}
