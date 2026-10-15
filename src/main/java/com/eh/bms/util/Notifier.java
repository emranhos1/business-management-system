package com.eh.bms.util;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

import java.util.logging.Logger;

/**
 * Success and error messages shown in the bar at the top of the main window.
 * A message pushes the page down, stays 5 seconds, fades away, and the page moves back up.
 * A new message replaces the old one.
 * Use it for every save/delete result and every general error.
 */
public final class Notifier {

    private static final Logger LOG = Logger.getLogger(Notifier.class.getName());
    private static final Duration SHOW_TIME = Duration.seconds(5);
    private static final Duration FADE_TIME = Duration.millis(400);

    private static Pane host;
    private static SequentialTransition running;

    private Notifier() {
    }

    /** The bar that holds messages; set by the main layout, null when it closes. */
    public static void setHost(Pane pane) {
        stop();
        host = pane;
    }

    public static void success(String message) {
        show(message, "toast-success");
    }

    public static void error(String message) {
        show(message, "toast-error");
    }

    private static void show(String message, String style) {
        if (host == null) {
            LOG.info("No message bar; message: " + message);
            return;
        }
        stop();
        Label toast = new Label(message);
        toast.getStyleClass().addAll("toast", style);
        toast.setWrapText(true);
        toast.setMaxWidth(Double.MAX_VALUE);
        host.getChildren().setAll(toast);
        setShown(true);

        FadeTransition fade = new FadeTransition(FADE_TIME, toast);
        fade.setToValue(0);
        running = new SequentialTransition(new PauseTransition(SHOW_TIME), fade);
        running.setOnFinished(e -> {
            host.getChildren().remove(toast);
            setShown(false);
        });
        running.play();
    }

    private static void stop() {
        if (running != null) {
            running.stop();
            running = null;
        }
        if (host != null) {
            host.getChildren().clear();
            setShown(false);
        }
    }

    /** The bar takes space only while a message shows; the page moves down, then back up. */
    private static void setShown(boolean shown) {
        host.setVisible(shown);
        host.setManaged(shown);
    }
}
