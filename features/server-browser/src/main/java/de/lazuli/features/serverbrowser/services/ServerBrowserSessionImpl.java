package de.lazuli.features.serverbrowser.services;

import de.lazuli.api.serverbrowser.ServerBrowserColumn;
import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;
import de.lazuli.api.serverbrowser.ServerBrowserSession;
import de.lazuli.api.serverbrowser.ServerBrowserSource;

import java.util.List;
import java.util.function.Consumer;

/**
 * {@link ServerBrowserSession} implementation composing one
 * {@link ServerBrowserQuery} + one {@link ServerBrowserTableModel} per
 * instance (spec Decision 2). Owns the mutable current sort/filter state,
 * session-lifetime only (FR3.7), and re-invokes
 * {@link ServerBrowserTableModel#apply} against the latest raw row list on
 * every mutation.
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserSession session = new ServerBrowserSessionImpl(new ServerBrowserQuery(...), new ServerBrowserTableModel());
 * session.start(ServerBrowserSource.INTERNET, rows -> ..., () -> ...);
 * }</pre>
 */
public final class ServerBrowserSessionImpl implements ServerBrowserSession {

    private final ServerBrowserQuery query;
    private final ServerBrowserTableModel tableModel;

    private List<ServerBrowserRow> rawRows = List.of();
    private ServerBrowserColumn sortColumn = ServerBrowserColumn.PING; // FR2.4 default
    private boolean ascending = true;
    private ServerBrowserFilterState filter = ServerBrowserFilterState.DEFAULT;
    private Consumer<List<ServerBrowserRow>> onRowsChanged;
    private boolean closed;

    public ServerBrowserSessionImpl(ServerBrowserQuery query, ServerBrowserTableModel tableModel) {
        this.query = query;
        this.tableModel = tableModel;
    }

    @Override
    public void start(ServerBrowserSource source, Consumer<List<ServerBrowserRow>> onRowsChanged, Runnable onRefreshComplete) {
        this.onRowsChanged = onRowsChanged;
        sortColumn = ServerBrowserColumn.PING;
        ascending = true;
        filter = ServerBrowserFilterState.DEFAULT;
        closed = false;
        query.start(source, this::onRawRowsChanged, onRefreshComplete);
    }

    @Override
    public void refresh() {
        query.refresh();
    }

    @Override
    public boolean isRefreshing() {
        return query.isRefreshing();
    }

    @Override
    public void setSortColumn(ServerBrowserColumn column) {
        if (column == sortColumn) {
            ascending = !ascending;
        } else {
            sortColumn = column;
            ascending = true;
        }
        publish();
    }

    @Override
    public void setFilter(ServerBrowserFilterState filter) {
        this.filter = filter;
        publish();
    }

    @Override
    public List<ServerBrowserRow> currentRows() {
        return tableModel.apply(rawRows, sortColumn, ascending, filter);
    }

    @Override
    public ServerBrowserColumn sortColumn() {
        return sortColumn;
    }

    @Override
    public boolean sortAscending() {
        return ascending;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        query.close();
        closed = true;
    }

    private void onRawRowsChanged(List<ServerBrowserRow> rows) {
        this.rawRows = rows;
        publish();
    }

    private void publish() {
        if (onRowsChanged != null) {
            onRowsChanged.accept(currentRows());
        }
    }
}
