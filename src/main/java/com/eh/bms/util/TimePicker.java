package com.eh.bms.util;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Popup;

import java.time.LocalTime;
import java.util.function.Supplier;

/**
 * Picks a time on a clock face, no typing: click the field, pick the hour on the clock, then the minute
 * (5-minute steps), and AM / PM in 12-hour mode. In 24-hour mode ({@code time_format}) the clock shows 1-12 outside
 * and 13-00 inside. "মুছুন" clears it. The field shows the time in the time format (Bangla digits in Bangla mode).
 */
public class TimePicker extends Button {

    private static final double FACE = 230;
    private static final double OUTER_RADIUS = 92;
    private static final double INNER_RADIUS = 60;
    private static final double NUMBER_SIZE = 34;
    private static final int MINUTE_STEP = 5;

    private final ObjectProperty<LocalTime> value = new SimpleObjectProperty<>(this, "value");
    /** Time the clock starts at when the field is empty (e.g. 9:00 AM for "in", 5:00 PM for "out"). */
    private Supplier<LocalTime> startTime = () -> LocalTime.of(9, 0);
    private String prompt = "";

    public TimePicker() {
        getStyleClass().setAll("button", "time-picker");
        setGraphic(Icons.of(Icons.CLOCK));
        setContentDisplay(ContentDisplay.RIGHT);
        setGraphicTextGap(8);
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);
        setFocusTraversable(true);
        value.addListener((obs, old, now) -> showValue());
        setOnAction(e -> openClock());
        showValue();
    }

    public ObjectProperty<LocalTime> valueProperty() {
        return value;
    }

    public LocalTime getValue() {
        return value.get();
    }

    public void setValue(LocalTime time) {
        value.set(time);
    }

    /** Grey text shown while no time is picked. */
    public void setPrompt(String prompt) {
        this.prompt = prompt == null ? "" : prompt;
        showValue();
    }

    /** Where the clock starts when no time is picked yet. */
    public void setStartTime(Supplier<LocalTime> startTime) {
        this.startTime = startTime;
    }

    /** The time in the {@code time_format} setting without seconds, e.g. "09:30 AM" or "21:30"; "" for null. */
    public static String text(LocalTime t) {
        return t == null ? "" : I18n.digits(t.format(java.time.format.DateTimeFormatter
                .ofPattern(DateUtil.timePattern(), java.util.Locale.ENGLISH)));
    }

    private void showValue() {
        LocalTime t = value.get();
        setText(t == null ? prompt : text(t));
        getStyleClass().remove("time-picker-empty");
        if (t == null) {
            getStyleClass().add("time-picker-empty");
        }
    }

    // ---------------------------------------------------------------- clock popup

    private void openClock() {
        new Clock().show();
    }

    /** The popup: picked time on top (hour : minute, AM/PM), the clock face, then clear / OK. */
    private final class Clock {
        private final boolean twelve = DateUtil.TIME_12.equals(DateUtil.timePattern());
        private final Popup popup = new Popup();
        private final Label hourLabel = new Label();
        private final Label minuteLabel = new Label();
        private final ToggleButton am = new ToggleButton("AM");
        private final ToggleButton pm = new ToggleButton("PM");
        private final Pane face = new Pane();
        private int hour;
        private int minute;
        private boolean pickingHour = true;

        Clock() {
            LocalTime start = value.get() != null ? value.get() : startTime.get();
            hour = start.getHour();
            minute = start.getMinute() - start.getMinute() % MINUTE_STEP;

            hourLabel.getStyleClass().add("clock-head-part");
            minuteLabel.getStyleClass().add("clock-head-part");
            hourLabel.setOnMouseClicked(e -> showHours());
            minuteLabel.setOnMouseClicked(e -> showMinutes());
            Label colon = new Label(":");
            colon.getStyleClass().add("clock-head-colon");
            HBox head = new HBox(4, hourLabel, colon, minuteLabel);
            head.setAlignment(Pos.CENTER_LEFT);
            if (twelve) {
                ToggleGroup half = new ToggleGroup();
                am.setToggleGroup(half);
                pm.setToggleGroup(half);
                am.getStyleClass().add("clock-half");
                pm.getStyleClass().add("clock-half");
                am.setOnAction(e -> setPm(false));
                pm.setOnAction(e -> setPm(true));
                Region gap = new Region();
                HBox.setHgrow(gap, Priority.ALWAYS);
                head.getChildren().addAll(gap, new VBox(4, am, pm));
            }
            head.getStyleClass().add("clock-head");

            face.setPrefSize(FACE, FACE);
            face.setMinSize(FACE, FACE);
            face.setMaxSize(FACE, FACE);

            Button clear = new Button(I18n.get("time.clear"));
            clear.getStyleClass().add("no-button");
            clear.setOnAction(e -> {
                value.set(null);
                popup.hide();
            });
            Button ok = new Button(I18n.get("button.ok"));
            ok.getStyleClass().add("save-button");
            ok.setOnAction(e -> commit());
            HBox buttons = new HBox(10, clear, ok);
            buttons.setAlignment(Pos.CENTER_RIGHT);

            VBox box = new VBox(12, head, face, buttons);
            box.getStyleClass().add("clock-popup");
            box.getStylesheets().add(WindowManager.stylesheet());
            popup.getContent().add(box);
            // Clicking outside closes it without changing the time
            popup.setAutoHide(true);
            showHours();
        }

        void show() {
            var bounds = TimePicker.this.localToScreen(TimePicker.this.getBoundsInLocal());
            if (bounds == null) {
                return;
            }
            popup.show(TimePicker.this, bounds.getMinX(), bounds.getMaxY() + 2);
        }

        private void commit() {
            value.set(LocalTime.of(hour, minute));
            popup.hide();
        }

        private void setPm(boolean afternoon) {
            if (afternoon && hour < 12) {
                hour += 12;
            } else if (!afternoon && hour >= 12) {
                hour -= 12;
            }
            refreshHead();
        }

        private void showHours() {
            pickingHour = true;
            face.getChildren().setAll(dial());
            if (twelve) {
                for (int h = 1; h <= 12; h++) {
                    int pick = h;
                    face.getChildren().add(number(h, OUTER_RADIUS, h % 12 == hour % 12, () -> pickHour(pick)));
                }
            } else {
                for (int h = 1; h <= 12; h++) {
                    int pick = h;
                    face.getChildren().add(number(h, OUTER_RADIUS, h == hour, () -> pickHour(pick)));
                }
                for (int h = 13; h <= 24; h++) {
                    int pick = h % 24;
                    face.getChildren().add(number(h - 12, INNER_RADIUS, pick == hour, pickText(pick), () -> pickHour(pick)));
                }
            }
            refreshHead();
        }

        private void showMinutes() {
            pickingHour = false;
            face.getChildren().setAll(dial());
            for (int i = 0; i < 12; i++) {
                int pick = i * MINUTE_STEP;
                face.getChildren().add(number(i == 0 ? 12 : i, OUTER_RADIUS, pick == minute,
                        String.format("%02d", pick), () -> pickMinute(pick)));
            }
            refreshHead();
        }

        /** Hour from the clock: in 12-hour mode it keeps the chosen AM / PM. */
        private void pickHour(int h) {
            if (twelve) {
                boolean afternoon = hour >= 12;
                hour = h % 12 + (afternoon ? 12 : 0);
            } else {
                hour = h;
            }
            showMinutes();
        }

        private void pickMinute(int m) {
            minute = m;
            commit();
        }

        private void refreshHead() {
            int shown = twelve ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
            hourLabel.setText(I18n.digits(String.format("%02d", shown)));
            minuteLabel.setText(I18n.digits(String.format("%02d", minute)));
            hourLabel.getStyleClass().remove("clock-head-active");
            minuteLabel.getStyleClass().remove("clock-head-active");
            (pickingHour ? hourLabel : minuteLabel).getStyleClass().add("clock-head-active");
            am.setSelected(hour < 12);
            pm.setSelected(hour >= 12);
        }

        private Circle dial() {
            Circle c = new Circle(FACE / 2, FACE / 2, FACE / 2);
            c.getStyleClass().add("clock-dial");
            return c;
        }

        private Button number(int position, double radius, boolean selected, Runnable action) {
            return number(position, radius, selected, String.valueOf(position), action);
        }

        /** A number on the clock at the place of {@code position} (1-12, like a clock), showing {@code label}. */
        private Button number(int position, double radius, boolean selected, String label, Runnable action) {
            Button b = new Button(I18n.digits(label));
            b.getStyleClass().setAll("button", "clock-number");
            if (selected) {
                b.getStyleClass().add("clock-number-selected");
            }
            if (radius < OUTER_RADIUS) {
                b.getStyleClass().add("clock-number-inner");
            }
            b.setMinSize(NUMBER_SIZE, NUMBER_SIZE);
            b.setPrefSize(NUMBER_SIZE, NUMBER_SIZE);
            b.setMaxSize(NUMBER_SIZE, NUMBER_SIZE);
            double angle = Math.toRadians(position * 30 - 90);
            b.setLayoutX(FACE / 2 + radius * Math.cos(angle) - NUMBER_SIZE / 2);
            b.setLayoutY(FACE / 2 + radius * Math.sin(angle) - NUMBER_SIZE / 2);
            b.setOnAction(e -> action.run());
            return b;
        }

        private String pickText(int h) {
            return String.format("%02d", h);
        }
    }
}
