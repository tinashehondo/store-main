package com.example.store.controller;

import org.springframework.test.web.servlet.ResultMatcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared checks on error responses. */
final class ProblemAssertions {

    /** Fragments that would reveal the stack, the framework or the SQL behind an error response. */
    private static final List<String> INTERNALS =
            List.of("Exception", "org.springframework", "jackson", "hibernate", "SQL", "at com.");

    private ProblemAssertions() {}

    static ResultMatcher noInternals() {
        return result -> {
            String body = result.getResponse().getContentAsString();
            INTERNALS.forEach(fragment -> assertThat(body).doesNotContainIgnoringCase(fragment));
        };
    }
}
