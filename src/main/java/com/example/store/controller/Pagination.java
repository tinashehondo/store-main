package com.example.store.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Pagination contract shared by every collection endpoint.
 *
 * <p>The body stays a plain JSON array (backward compatible); paging metadata travels in headers: {@code X-Total-Count}
 * and an RFC 8288 {@code Link} header with {@code first}, {@code prev}, {@code next} and {@code last} relations. Pages
 * are always ordered by id ascending so they are stable, and the page size is clamped to {@link #MAX_PAGE_SIZE} to keep
 * every response bounded.
 */
public final class Pagination {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    /**
     * Highest accepted page number: the row offset {@code page * size} must fit in an {@code int}, or Spring Data fails
     * with a 500. Even at the maximum size that is past the end of any realistic table.
     */
    public static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    public static final String TOTAL_COUNT_HEADER = "X-Total-Count";

    private static final Sort ORDER_BY_ID = Sort.by(Sort.Direction.ASC, "id");

    private Pagination() {}

    /**
     * Callers validate {@code 0 <= page <= MAX_PAGE} and {@code size >= 1}; a size above the maximum is clamped, not
     * rejected.
     */
    public static Pageable pageRequest(int page, int size) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), ORDER_BY_ID);
    }

    /** A 200 response with the page content as body and the paging headers built from the current request URL. */
    public static <T> ResponseEntity<List<T>> response(Page<T> page) {
        return ResponseEntity.ok()
                .headers(headers(page, ServletUriComponentsBuilder.fromCurrentRequest()))
                .body(page.getContent());
    }

    /**
     * Builds the paging headers. Links keep every query parameter of {@code requestUri} (e.g. a search term) and only
     * replace {@code page} and {@code size}; {@code size} is the effective, possibly clamped, value.
     */
    static HttpHeaders headers(Page<?> page, UriComponentsBuilder requestUri) {
        int current = page.getNumber();
        int size = page.getSize();
        // An empty result still has one (empty) page, so first and last both point at page 0.
        int last = Math.max(page.getTotalPages() - 1, 0);

        List<String> links = new ArrayList<>();
        links.add(link(requestUri, 0, size, "first"));
        if (current > 0) {
            // Past the end, prev leads back to the last page that has content.
            links.add(link(requestUri, Math.min(current - 1, last), size, "prev"));
        }
        if (current < last) {
            links.add(link(requestUri, current + 1, size, "next"));
        }
        links.add(link(requestUri, last, size, "last"));

        HttpHeaders headers = new HttpHeaders();
        headers.set(TOTAL_COUNT_HEADER, String.valueOf(page.getTotalElements()));
        headers.set(HttpHeaders.LINK, String.join(", ", links));
        return headers;
    }

    private static String link(UriComponentsBuilder requestUri, int page, int size, String rel) {
        // build() rather than encode(): the request URI is already encoded, and page/size are plain integers.
        String uri = requestUri
                .cloneBuilder()
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .build()
                .toUriString();
        return "<" + uri + ">; rel=\"" + rel + "\"";
    }
}
