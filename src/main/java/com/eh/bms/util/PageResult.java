package com.eh.bms.util;

import java.util.List;

/** One page of rows plus the total row count across all pages. */
public record PageResult<T>(List<T> items, long total) {

    public PageResult {
        items = List.copyOf(items);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), 0);
    }
}
