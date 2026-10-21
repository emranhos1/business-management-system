package com.eh.bms.controller;

import com.eh.bms.service.AppConfigService;
import com.eh.bms.service.SaleService;
import com.eh.bms.service.SaleService.Memo;
import com.eh.bms.util.I18n;
import com.eh.bms.util.MemoPaper;
import com.eh.bms.util.NodePrinter;
import com.eh.bms.util.Notifier;
import com.eh.bms.util.WindowManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Memo preview (N6): the memo on its paper (Settings), then Print opens the printer dialog. */
public class MemoViewController {

    private static final Logger LOG = Logger.getLogger(MemoViewController.class.getName());
    private static final String FXML = "/fxml/views/memo-view.fxml";

    @FXML private StackPane paperHolder;

    private final SaleService service = new SaleService();
    private final AppConfigService configService = new AppConfigService();
    private Memo memo;
    private MemoPaper paper;

    /** Opens the preview of a sale's memo. */
    public static void open(long saleId) {
        FXMLLoader loader = WindowManager.loader(FXML);
        Parent root = WindowManager.load(loader, FXML);
        MemoViewController controller = loader.getController();
        if (!controller.load(saleId)) {
            return;
        }
        String memoNo = SalesListController.memoText(controller.memo.details().sale());
        WindowManager.dialog(root, I18n.get("memo.window_title", memoNo)).showAndWait();
    }

    private boolean load(long saleId) {
        try {
            memo = service.memo(saleId);
            paper = configService.memoPaper();
            paperHolder.getChildren().setAll(SaleMemo.build(memo, paper));
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not load memo", e);
            Notifier.error(I18n.get("msg.error"));
            return false;
        }
    }

    @FXML
    private void onPrint() {
        // A fresh copy for the printer, so the preview is never scaled or cut
        NodePrinter.Status status = NodePrinter.print(SaleMemo.build(memo, paper), paper, window());
        switch (status) {
            case DONE -> {
                Notifier.success(I18n.get("memo.printed"));
                window().close();
            }
            case NO_PRINTER -> Notifier.error(I18n.get("memo.error.no_printer"));
            case FAILED -> Notifier.error(I18n.get("memo.error.print"));
            case CANCELLED -> {
                // The user closed the printer dialog; the preview stays open
            }
        }
    }

    @FXML
    private void onClose() {
        window().close();
    }

    private Stage window() {
        return (Stage) paperHolder.getScene().getWindow();
    }
}
