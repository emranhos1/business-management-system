package com.eh.bms;

import javafx.application.Application;

/**
 * Entry point. A separate launcher class (not extending Application) lets the app
 * run from a plain classpath / fat JAR without JavaFX module errors.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(BmsApplication.class, args);
    }
}
