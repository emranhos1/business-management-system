package com.eh.bms.util;

import com.eh.bms.config.AppConfig;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

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
    /** Restored (not maximized) main window: share of the screen. */
    private static final double RESTORED_WIDTH = 0.90;
    private static final double RESTORED_HEIGHT = 0.85;
    private static final double MAIN_MIN_WIDTH = 1024;
    private static final double MAIN_MIN_HEIGHT = 680;

    /** Window property: the popup resizes to its content when form errors appear or go (FormValidator). */
    public static final String AUTO_SIZE = "bms.autoSize";

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

    /** Opens maximized; restore-down gives 90% of the screen width and 85% of its height, centered. */
    public static void showMain() {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.max(MAIN_MIN_WIDTH, screen.getWidth() * RESTORED_WIDTH);
        double height = Math.max(MAIN_MIN_HEIGHT, screen.getHeight() * RESTORED_HEIGHT);

        Stage stage = newStage();
        stage.setScene(scene(load(MAIN_FXML), width, height));
        stage.setMinWidth(MAIN_MIN_WIDTH);
        stage.setMinHeight(MAIN_MIN_HEIGHT);
        // JavaFX does not keep a size set before the first maximize, so set it on every restore-down
        stage.maximizedProperty().addListener((obs, was, maximized) -> {
            if (!maximized) {
                stage.setWidth(width);
                stage.setHeight(height);
                stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2);
                stage.setY(screen.getMinY() + (screen.getHeight() - height) / 2);
            }
        });
        stage.setMaximized(true);
        swapTo(stage);
    }

    /**
     * A popup window (e.g. an add/edit form) over the main window. The main window cannot be used
     * until it closes. Call {@code showAndWait()} on the result.
     */
    public static Stage dialog(Parent root, String title) {
        Stage stage = new Stage();
        stage.setTitle(title);
        AppIcons.apply(stage);
        if (current != null) {
            stage.initOwner(current);
        }
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setResizable(false);
        stage.setScene(scene(root));
        stage.getProperties().put(AUTO_SIZE, Boolean.TRUE);
        return stage;
    }

    /** Resizes the popup holding this node to its content (after rows or error lines appear or hide). */
    public static void fitPopup(Node node) {
        if (node.getScene() != null && node.getScene().getWindow() instanceof Stage stage
                && Boolean.TRUE.equals(stage.getProperties().get(AUTO_SIZE))) {
            Platform.runLater(stage::sizeToScene);
        }
    }

    /** The open app window (login or main); owner for dialogs. Null before the first window. */
    public static Window currentWindow() {
        return current;
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
        scene.getStylesheets().add(stylesheet());
        return scene;
    }

    /** The app stylesheet, for windows not made here (e.g. Alert dialogs). */
    public static String stylesheet() {
        URL css = Objects.requireNonNull(WindowManager.class.getResource(MAIN_CSS), "Missing " + MAIN_CSS);
        return css.toExternalForm();
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
