package com.example.store.controller;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.service.CustomerService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/customer")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public ResponseEntity<List<CustomerDTO>> getAllCustomers(
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_SIZE) @Positive int size) {
        return Pagination.response(customerService.findAll(Pagination.pageRequest(page, size)));
    }

    /**
     * Same endpoint as the list, selected when {@code name} is present: customers whose name contains it, ignoring
     * case. Paginated exactly like the list; the paging links keep {@code name}. {@code @NotBlank} would also reject a
     * missing value, which cannot happen here, so blankness is checked with a pattern (DOTALL so a newline is not
     * "blank").
     */
    @GetMapping(params = "name")
    public ResponseEntity<List<CustomerDTO>> searchCustomers(
            @RequestParam @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String name,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_SIZE) @Positive int size) {
        return Pagination.response(customerService.search(name, Pagination.pageRequest(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDTO createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        return customerService.create(request);
    }
}
