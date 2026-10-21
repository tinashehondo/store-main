package com.example.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = TextConstraints.NO_NUL, message = TextConstraints.NO_NUL_MESSAGE)
                String name) {}
