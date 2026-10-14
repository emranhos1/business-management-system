package com.eh.bms.util;

import com.eh.bms.service.AppConfigService;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * A TableView with a Previous / Page X of Y / Next bar. Rows are loaded one page at a time
 * (page size = {@code items_per_page} from app_config), so large tables stay fast.
 * <pre>{@code
 * PaginatedTable<Party> table = new PaginatedTable<>((offset, limit) -> partyService.page(offset, limit));
 * table.getTableView().getColumns().add(nameColumn);
 * table.reload();
 * }</pre>
 */
public class PaginatedTable<T> extends VBox {

    /** Loads rows {@code offset .. offset+limit-1} and the total row count. */
    @FunctionalInterface
    public interface PageLoader<T> {
        PageResult<T> load(int offset, int limit);
    }

    private final TableView<T> tableView = new TableView<>();
    private final Button previousButton = new Button();
    private final Button nextButton = new Button();
    private final Label pageLabel = new Label();

    private final PageLoader<T> loader;
    private final int pageSize;
    private int page = 1;
    private int totalPages = 1;

    public PaginatedTable(PageLoader<T> loader) {
        this(loader, new AppConfigService().itemsPerPage());
    }

    public PaginatedTable(PageLoader<T> loader, int pageSize) {
        this.loader = loader;
        this.pageSize = Math.max(1, pageSize);

        Label empty = new Label();
        empty.textProperty().bind(I18n.bind("msg.no_data"));
        tableView.setPlaceholder(empty);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        previousButton.textProperty().bind(I18n.bind("pagination.previous"));
        nextButton.textProperty().bind(I18n.bind("pagination.next"));
        previousButton.setOnAction(event -> goTo(page - 1));
        nextButton.setOnAction(event -> goTo(page + 1));

        HBox bar = new HBox(10, previousButton, pageLabel, nextButton);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.getStyleClass().add("pagination-bar");

        setSpacing(8);
        getStyleClass().add("paginated-table");
        getChildren().addAll(tableView, bar);
        updateBar();
    }

    public TableView<T> getTableView() {
        return tableView;
    }

    public int getPage() {
        return page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getPageSize() {
        return pageSize;
    }

    /** Back to page 1, e.g. after a filter changes. */
    public void reload() {
        goTo(1);
    }

    /** Reloads the current page, e.g. after an edit. */
    public void refresh() {
        goTo(page);
    }

    private void goTo(int target) {
        PageResult<T> result = loader.load((Math.max(1, target) - 1) * pageSize, pageSize);
        totalPages = (int) Math.max(1, (result.total() + pageSize - 1) / pageSize);
        if (target > totalPages && result.total() > 0) {
            // Rows were deleted and this page no longer exists
            goTo(totalPages);
            return;
        }
        page = Math.max(1, Math.min(target, totalPages));
        tableView.getItems().setAll(result.items());
        updateBar();
    }

    private void updateBar() {
        pageLabel.textProperty().bind(I18n.bind("pagination.page_of", page, totalPages));
        previousButton.setDisable(page <= 1);
        nextButton.setDisable(page >= totalPages);
    }
}
