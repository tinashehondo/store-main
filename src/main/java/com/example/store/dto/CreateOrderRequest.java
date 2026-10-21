package com.example.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * {@code productIds} is a set, so duplicates collapse. Its size is capped because each id becomes a bind parameter of
 * the lookup query and a row of the order.
 */
public record CreateOrderRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = TextConstraints.NO_NUL, message = TextConstraints.NO_NUL_MESSAGE)
                String description,
        @NotNull Long customerId,
        @NotEmpty @Size(max = 100) Set<@NotNull Long> productIds) {}
