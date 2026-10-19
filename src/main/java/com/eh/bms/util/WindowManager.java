package com.eh.bms.util;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
    private static final String REGISTER_FXML = "/fxml/register.fxml";
    private static final String MAIN_FXML = "/fxml/main.fxml";

    /** Login window width and height; it grows taller only for the update banner (LoginController). */
    public static final double LOGIN_SIZE = 520;
    /** Restored (not maximized) main window: share of the screen. */
    private static final double RESTORED_WIDTH = 0.90;
    private static final double RESTORED_HEIGHT = 0.85;
    private static final double MAIN_MIN_WIDTH = 1024;
    private static final double MAIN_MIN_HEIGHT = 680;

    /** Window property: the popup resizes to its content when form errors appear or go (FormValidator). */
    public static final String AUTO_SIZE = "bms.autoSize";
    /** Height of the strip under a popup's scrolling part that holds the "more below" arrow. */
    private static final double MORE_STRIP_HEIGHT = 44;
    /** Window property: the popup's scrolling part. */
    private static final String SCROLL = "bms.scroll";

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

    /**
     * The registration page (N1) in place of the login page: same look, its window as tall as the form, and it
     * grows when field errors show (FormValidator, {@link #AUTO_SIZE}).
     */
    public static void showRegister() {
        Stage stage = newStage();
        stage.setScene(scene(load(REGISTER_FXML)));
        stage.setResizable(false);
        stage.getProperties().put(AUTO_SIZE, Boolean.TRUE);
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

        // The content scrolls when the popup is taller than the screen (e.g. a long form on a small screen)
        ScrollPane scroll = new ScrollPane(root);
        // Not fitToHeight: with it, JavaFX hides the scroll bar even when the form is taller than the popup
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("dialog-scroll");
        stage.getProperties().put(SCROLL, scroll);
        // A form's button row (Save / Cancel / Delete) stays fixed under the scrolling part, always in sight
        Node body = withMoreArrow(scroll, root);
        if (root.getStyleClass().contains("dialog-form") && root instanceof Pane pane) {
            Node buttons = pane.getChildren().stream()
                    .filter(n -> n.getStyleClass().contains("dialog-buttons")).findFirst().orElse(null);
            if (buttons != null) {
                pane.getChildren().remove(buttons);
                buttons.getStyleClass().add("dialog-footer");
                VBox.setVgrow(body, Priority.ALWAYS);
                body = new VBox(body, buttons);
            }
        }
        HBox.setHgrow(body, Priority.ALWAYS);
        HBox frame = new HBox(titleStrip(stage, title), body);
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
                // A form keeps its full height inside the scroll (never squeezed, so no text is cut to "...")
                if (root instanceof Region region) {
                    region.setMinHeight(Region.USE_PREF_SIZE);
                }
                stage.sizeToScene();
                fitToScreen(stage);
            }
            centerOnWorkArea(stage, owner);
        });
        return stage;
    }

    /**
     * Centers the popup on the main window's work area (right of the sidebar), or on its owner popup
     * (e.g. a Yes/No question over a form). Done after the final size is known; JavaFX's own centering
     * uses the size before {@code sizeToScene}, so it lands off-centre.
     */
    private static void centerOnWorkArea(Stage stage, Window owner) {
        if (owner == null) {
            return;
        }
        Bounds area = null;
        if (owner == current && owner.getScene() != null) {
            Node work = owner.getScene().lookup("#workArea");
            area = work == null ? null : work.localToScreen(work.getLayoutBounds());
        }
        if (area == null) {
            area = new BoundingBox(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
        }
        Rectangle2D screen = screenOf(stage);
        double x = area.getMinX() + (area.getWidth() - stage.getWidth()) / 2;
        double y = area.getMinY() + (area.getHeight() - stage.getHeight()) / 2;
        // Never past the screen's edges (taskbar included)
        stage.setX(Math.max(screen.getMinX(), Math.min(x, screen.getMaxX() - stage.getWidth())));
        stage.setY(Math.max(screen.getMinY(), Math.min(y, screen.getMaxY() - stage.getHeight())));
    }

    /**
     * The popup's scrolling part with a round down-arrow under it while more is hidden below (gone at the
     * bottom); clicking it scrolls down. The arrow sits in its own strip, shown only while the content is
     * taller than the popup, so it never covers the content.
     */
    private static Node withMoreArrow(ScrollPane scroll, Parent content) {
        Region arrow = new Region();
        arrow.getStyleClass().add("scroll-more-arrow");
        StackPane more = new StackPane(arrow);
        more.getStyleClass().add("scroll-more");
        more.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        more.setOnMouseClicked(e -> scroll.setVvalue(scroll.getVmax()));
        StackPane strip = new StackPane(more);
        strip.setPadding(new Insets(4, 0, 4, 0));
        strip.setMinHeight(MORE_STRIP_HEIGHT);
        strip.setPrefHeight(MORE_STRIP_HEIGHT);
        strip.setMaxHeight(MORE_STRIP_HEIGHT);
        // Is the content taller than the room the scroll part would have without the strip?
        Runnable update = () -> {
            double room = scroll.getViewportBounds().getHeight() + (strip.isManaged() ? MORE_STRIP_HEIGHT : 0);
            boolean needed = content.getLayoutBounds().getHeight() > room + 1;
            if (needed != strip.isManaged()) {
                strip.setManaged(needed);
                strip.setVisible(needed);
            }
        };
        strip.setManaged(false);
        strip.setVisible(false);
        scroll.viewportBoundsProperty().addListener(o -> update.run());
        content.layoutBoundsProperty().addListener(o -> update.run());
        more.visibleProperty().bind(scroll.vvalueProperty().lessThan(scroll.vmaxProperty().subtract(0.001)));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return new VBox(scroll, strip);
    }

    /**
     * A list popup (ledger, sale details): a fixed size instead of fitting its content, never bigger than the
     * screen the app is on (e.g. 1040 px on a 1024 px screen). Its content fills that height (the table
     * grows or shrinks), so the buttons under the table are never pushed out of the window; content that
     * cannot shrink that far scrolls instead (with the scroll bar). Call before showing.
     */
    public static void sizeListWindow(Stage stage, double width, double height) {
        stage.getProperties().remove(AUTO_SIZE);
        if (stage.getProperties().get(SCROLL) instanceof ScrollPane scroll
                && scroll.getContent() instanceof Region content) {
            // Filling the height while the content is taller than the window would hide the scroll bar
            scroll.viewportBoundsProperty().addListener((obs, old, view) ->
                    scroll.setFitToHeight(content.minHeight(view.getWidth()) <= view.getHeight()));
        }
        Window owner = stage.getOwner() != null ? stage.getOwner() : current;
        Rectangle2D screen = owner == null ? Screen.getPrimary().getVisualBounds() : screenOf(owner);
        stage.setWidth(Math.min(width, screen.getWidth()));
        stage.setHeight(Math.min(height, screen.getHeight()));
    }

    /** Caps a popup's height at the screen's visible height; its content then scrolls. */
    private static void fitToScreen(Stage stage) {
        double max = screenOf(stage).getHeight();
        if (stage.getHeight() > max) {
            stage.setHeight(max);
        }
    }

    /** Visible bounds (without the taskbar) of the screen the window is on. */
    private static Rectangle2D screenOf(Window window) {
        return Screen.getScreensForRectangle(window.getX(), window.getY(), Math.max(1, window.getWidth()),
                        Math.max(1, window.getHeight())).stream()
                .findFirst().orElse(Screen.getPrimary()).getVisualBounds();
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
            Platform.runLater(() -> {
                stage.sizeToScene();
                fitToScreen(stage);
                // Grown past the bottom of the screen: move it up
                Rectangle2D screen = screenOf(stage);
                if (stage.getY() + stage.getHeight() > screen.getMaxY()) {
                    stage.setY(Math.max(screen.getMinY(), screen.getMaxY() - stage.getHeight()));
                }
            });
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
        // Follows the language (taskbar and title bar)
        stage.titleProperty().bind(I18n.bind("app.title"));
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
