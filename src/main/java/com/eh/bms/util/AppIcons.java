package com.eh.bms.util;

import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Objects;

/** BMS icons for windows (title bar + taskbar). The OS picks the best size. */
public final class AppIcons {

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    private static List<Image> icons;

    private AppIcons() {
    }

    public static synchronized List<Image> all() {
        if (icons == null) {
            Image[] loaded = new Image[SIZES.length];
            for (int i = 0; i < SIZES.length; i++) {
                loaded[i] = image(SIZES[i]);
            }
            icons = List.of(loaded);
        }
        return icons;
    }

    /** One icon size, e.g. 128 for the splash screen. */
    public static Image image(int size) {
        String path = "/icons/bms-" + size + ".png";
        return new Image(Objects.requireNonNull(AppIcons.class.getResource(path), "Missing " + path).toExternalForm());
    }

    /** Sets the BMS icon on a window. Works for dialogs too (pass the dialog's window). */
    public static void apply(Window window) {
        if (window instanceof Stage stage) {
            stage.getIcons().setAll(all());
        }
    }
}
