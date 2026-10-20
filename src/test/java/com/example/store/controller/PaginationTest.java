package com.example.store.controller;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationTest {

    private static final String BASE = "http://localhost/order";

    @Test
    void pageRequestSortsByIdAscending() {
        Pageable pageable = Pagination.pageRequest(3, 25);

        assertThat(pageable.getPageNumber()).isEqualTo(3);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Test
    void pageRequestClampsSizeToMaximum() {
        assertThat(Pagination.pageRequest(0, 101).getPageSize()).isEqualTo(Pagination.MAX_PAGE_SIZE);
        assertThat(Pagination.pageRequest(0, Integer.MAX_VALUE).getPageSize()).isEqualTo(Pagination.MAX_PAGE_SIZE);
        assertThat(Pagination.pageRequest(0, 100).getPageSize()).isEqualTo(100);
    }

    @Test
    void firstPageHasNoPrev() {
        HttpHeaders headers = headers(page(0, 10, 95), BASE);

        assertThat(headers.getFirst(Pagination.TOTAL_COUNT_HEADER)).isEqualTo("95");
        assertThat(headers.getFirst(HttpHeaders.LINK))
                .isEqualTo("<" + BASE + "?page=0&size=10>; rel=\"first\", "
                        + "<" + BASE + "?page=1&size=10>; rel=\"next\", "
                        + "<" + BASE + "?page=9&size=10>; rel=\"last\"");
    }

    @Test
    void middlePageHasAllRelations() {
        HttpHeaders headers = headers(page(4, 10, 95), BASE);

        assertThat(headers.getFirst(HttpHeaders.LINK))
                .isEqualTo("<" + BASE + "?page=0&size=10>; rel=\"first\", "
                        + "<" + BASE + "?page=3&size=10>; rel=\"prev\", "
                        + "<" + BASE + "?page=5&size=10>; rel=\"next\", "
                        + "<" + BASE + "?page=9&size=10>; rel=\"last\"");
    }

    @Test
    void lastPageHasNoNext() {
        HttpHeaders headers = headers(page(9, 10, 95), BASE);

        assertThat(headers.getFirst(HttpHeaders.LINK))
                .isEqualTo("<" + BASE + "?page=0&size=10>; rel=\"first\", "
                        + "<" + BASE + "?page=8&size=10>; rel=\"prev\", "
                        + "<" + BASE + "?page=9&size=10>; rel=\"last\"");
    }

    @Test
    void pageBeyondTheEndLinksPrevToLastPage() {
        HttpHeaders headers = headers(page(50, 10, 95), BASE);

        assertThat(headers.getFirst(HttpHeaders.LINK))
                .isEqualTo("<" + BASE + "?page=0&size=10>; rel=\"first\", "
                        + "<" + BASE + "?page=9&size=10>; rel=\"prev\", "
                        + "<" + BASE + "?page=9&size=10>; rel=\"last\"");
    }

    @Test
    void emptyResultLinksFirstAndLastToPageZero() {
        HttpHeaders headers = headers(page(0, 20, 0), BASE);

        assertThat(headers.getFirst(Pagination.TOTAL_COUNT_HEADER)).isEqualTo("0");
        assertThat(headers.getFirst(HttpHeaders.LINK))
                .isEqualTo("<" + BASE + "?page=0&size=20>; rel=\"first\", " + "<" + BASE
                        + "?page=0&size=20>; rel=\"last\"");
    }

    @Test
    void linksKeepOtherQueryParametersAndReplacePageAndSize() {
        HttpHeaders headers = headers(page(0, 100, 150), BASE + "?name=J%C3%B6rg%20M&page=0&size=500");

        assertThat(headers.getFirst(HttpHeaders.LINK))
                .startsWith("<" + BASE + "?name=J%C3%B6rg%20M&page=0&size=100>; rel=\"first\"")
                .contains("<" + BASE + "?name=J%C3%B6rg%20M&page=1&size=100>; rel=\"next\"");
    }

    private static HttpHeaders headers(Page<?> page, String requestUri) {
        return Pagination.headers(page, UriComponentsBuilder.fromUriString(requestUri));
    }

    private static Page<Object> page(int number, int size, long total) {
        long remaining = Math.max(0, total - (long) number * size);
        List<Object> content = Collections.nCopies((int) Math.min(size, remaining), new Object());
        return new PageImpl<>(content, Pagination.pageRequest(number, size), total);
    }
}
