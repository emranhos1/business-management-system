package com.eh.bms.util;

import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Pages with input that is not saved yet (e.g. a sale being typed, the settings form). Before something that
 * reloads the page (the language switch), {@link #resolve()} asks the user to save first, go on without
 * saving, or stay. A page registers once in its initialize():
 * <pre>{@code UnsavedWork.register(rootNode, () -> hasChanges, () -> saveAndReturnTrueIfSaved());}</pre>
 * Only pages still on screen count (the owner node is in a scene); old pages are forgotten.
 */
public final class UnsavedWork {

    private record Guard(BooleanSupplier hasUnsaved, BooleanSupplier save) {
    }

    private static final Map<Node, Guard> GUARDS = new WeakHashMap<>();

    private UnsavedWork() {
    }

    /**
     * @param owner      a node of the page (it counts only while it is on screen)
     * @param hasUnsaved true while the page has unsaved input
     * @param save       saves it the usual way (with its own checks and Yes/No); true only if it was saved
     */
    public static void register(Node owner, BooleanSupplier hasUnsaved, BooleanSupplier save) {
        GUARDS.put(owner, new Guard(hasUnsaved, save));
    }

    /** True to go on (nothing unsaved, saved, or the user chose not to save); false to stay. */
    public static boolean resolve() {
        List<Guard> open = new ArrayList<>();
        GUARDS.forEach((node, guard) -> {
            if (node.getScene() != null && guard.hasUnsaved().getAsBoolean()) {
                open.add(guard);
            }
        });
        for (Guard guard : open) {
            switch (Dialogs.askSave(I18n.get("msg.unsaved_question"))) {
                case SAVE -> {
                    if (!guard.save().getAsBoolean()) {
                        return false;
                    }
                }
                case DISCARD -> {
                    // go on without saving
                }
                case CANCEL -> {
                    return false;
                }
            }
        }
        return true;
    }
}
