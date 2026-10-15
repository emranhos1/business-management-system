package com.eh.bms.util;

import com.eh.bms.config.AppConfig;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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
        return dialog(root, title, current);
    }

    /**
     * A popup over {@code owner}. It has no window title bar (every popup closes with its own button or Esc);
     * the title stands turned 90° on a strip down the left side, which also drags the popup. The popup
     * casts a shadow, and over the main window it opens centered on the part right of the sidebar.
     */
    public static Stage dialog(Parent root, String title, Window owner) {
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setTitle(title);
        AppIcons.apply(stage);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setResizable(false);

        HBox.setHgrow(root, Priority.ALWAYS);
        HBox frame = new HBox(titleStrip(stage, title), root);
        frame.getStyleClass().add("popup-frame");
        // Transparent margin around the popup, so its shadow has room to show
        StackPane shadowHost = new StackPane(frame);
        shadowHost.getStyleClass().add("popup-shadow-host");
        Scene scene = scene(shadowHost);
        scene.setFill(Color.TRANSPARENT);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        stage.getProperties().put(AUTO_SIZE, Boolean.TRUE);
        stage.setOnShown(e -> {
            // CSS (fonts, padding) is in place only now; size again so no edge is cut off
            if (Boolean.TRUE.equals(stage.getProperties().get(AUTO_SIZE))) {
                stage.sizeToScene();
            }
            centerOnWorkArea(stage, owner);
        });
        return stage;
    }

    /** Centers the popup on the main window's work area (right of the sidebar); other owners keep JavaFX's centering. */
    private static void centerOnWorkArea(Stage stage, Window owner) {
        if (owner == null || owner != current || owner.getScene() == null) {
            return;
        }
        Node work = owner.getScene().lookup("#workArea");
        Bounds area = work == null ? null : work.localToScreen(work.getLayoutBounds());
        if (area == null) {
            return;
        }
        stage.setX(area.getMinX() + Math.max(0, (area.getWidth() - stage.getWidth()) / 2));
        stage.setY(area.getMinY() + Math.max(0, (area.getHeight() - stage.getHeight()) / 2));
    }

    /** Left strip with the whole title turned 90° (read bottom to top); dragging it moves the popup. */
    private static Node titleStrip(Stage stage, String title) {
        Label label = new Label(title);
        label.getStyleClass().add("popup-title");
        label.setRotate(-90);
        // A Group lays out the turned label by its turned size, so the strip is as tall as the full title
        VBox strip = new VBox(new Group(label));
        strip.getStyleClass().add("popup-title-strip");
        strip.setAlignment(Pos.CENTER);
        double[] grab = new double[2];
        strip.setOnMousePressed(e -> {
            grab[0] = stage.getX() - e.getScreenX();
            grab[1] = stage.getY() - e.getScreenY();
        });
        strip.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() + grab[0]);
            stage.setY(e.getScreenY() + grab[1]);
        });
        return strip;
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
