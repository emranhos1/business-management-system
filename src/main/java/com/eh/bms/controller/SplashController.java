package com.eh.bms.controller;

import com.eh.bms.config.AppConfig;
import com.eh.bms.util.I18n;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** Splash screen shown while the app connects to the database and seeds data. */
public class SplashController {

    @FXML private Label titleLabel;
    @FXML private Label versionLabel;
    @FXML private Label statusLabel;

    @FXML
    private void initialize() {
        titleLabel.textProperty().bind(I18n.bind("app.title"));
        versionLabel.textProperty().bind(I18n.bind("splash.version", AppConfig.get().getString("app.version")));
    }

    /** Text of the current startup step. Bind it to the startup task's message. */
    public StringProperty statusProperty() {
        return statusLabel.textProperty();
    }
}
