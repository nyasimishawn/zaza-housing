package com.zaza.housing.util;

import com.zaza.housing.exception.ApiException;
import org.springframework.http.HttpStatus;

public final class Pagination {
    private Pagination() { }
    public static void validate(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Limit must be 1-100 and offset must be nonnegative.");
        }
    }
}
