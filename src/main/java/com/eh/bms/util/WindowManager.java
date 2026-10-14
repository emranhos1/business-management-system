package com.eh.bms.util;

import com.eh.bms.config.AppConfig;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/**
 * Opens the app windows and loads FXML. Each screen gets its own window:
 * login is small and fixed, the main layout is resizable and opens maximized.
 * Call on the JavaFX thread.
 */
public final class WindowManager {

    private static final String MAIN_CSS = "/css/main.css";
    private static final String LOGIN_FXML = "/fxml/login.fxml";
    private static final String MAIN_FXML = "/fxml/main.fxml";

    private static final double LOGIN_SIZE = 500;
    private static final double MAIN_WIDTH = 1280;
    private static final double MAIN_HEIGHT = 800;
    private static final double MAIN_MIN_WIDTH = 1024;
    private static final double MAIN_MIN_HEIGHT = 680;

    private static Stage current;

    private WindowManager() {
    }

    public static void showLogin() {
        Stage stage = newStage();
        stage.setScene(scene(load(LOGIN_FXML), LOGIN_SIZE, LOGIN_SIZE));
        stage.setResizable(false);
        stage.centerOnScreen();
        swapTo(stage);
    }

    public static void showMain() {
        Stage stage = newStage();
        stage.setScene(scene(load(MAIN_FXML), MAIN_WIDTH, MAIN_HEIGHT));
        stage.setMinWidth(MAIN_MIN_WIDTH);
        stage.setMinHeight(MAIN_MIN_HEIGHT);
        stage.setMaximized(true);
        swapTo(stage);
    }

    /** Loader for an FXML file; {@code %key} in the FXML resolves from the current language. */
    public static FXMLLoader loader(String fxml) {
        URL url = Objects.requireNonNull(WindowManager.class.getResource(fxml), "Missing " + fxml);
        return new FXMLLoader(url, I18n.getBundle());
    }

    public static Parent load(String fxml) {
        return load(loader(fxml), fxml);
    }

    public static Parent load(FXMLLoader loader, String fxml) {
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + fxml, e);
        }
    }

    /** Scene sized to its content, with the app stylesheet. */
    public static Scene scene(Parent root) {
        return withCss(new Scene(root));
    }

    public static Scene scene(Parent root, double width, double height) {
        return withCss(new Scene(root, width, height));
    }

    private static Scene withCss(Scene scene) {
        URL css = Objects.requireNonNull(WindowManager.class.getResource(MAIN_CSS), "Missing " + MAIN_CSS);
        scene.getStylesheets().add(css.toExternalForm());
        return scene;
    }

    private static Stage newStage() {
        Stage stage = new Stage();
        stage.setTitle(AppConfig.get().getString("app.name"));
        AppIcons.apply(stage);
        return stage;
    }

    /** Shows the new window before closing the old one, so JavaFX never sees zero windows and exits. */
    private static void swapTo(Stage next) {
        next.show();
        if (current != null) {
            current.close();
        }
        current = next;
    }
}
