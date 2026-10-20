package com.example.store.controller;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.CustomerOrderDTO;
import com.example.store.service.CustomerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerController.class)
class CustomerControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void getAllCustomersReturnsPlainArrayWithPagingHeaders() throws Exception {
        when(customerService.findAll(any()))
                .thenReturn(new PageImpl<>(
                        List.of(customer(1L, "John Doe", order(7L, "Test Order"))), Pagination.pageRequest(0, 20), 41));

        mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("John Doe"))
                .andExpect(jsonPath("$[0].orders[0].id").value(7))
                .andExpect(jsonPath("$[0].orders[0].description").value("Test Order"))
                .andExpect(header().string("X-Total-Count", "41"))
                .andExpect(header().string(
                                "Link",
                                "<http://localhost/customer?page=0&size=20>; rel=\"first\", "
                                        + "<http://localhost/customer?page=1&size=20>; rel=\"next\", "
                                        + "<http://localhost/customer?page=2&size=20>; rel=\"last\""));
    }

    @Test
    void getAllCustomersDefaultsToFirstPageOfTwentyOrderedById() throws Exception {
        when(customerService.findAll(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/customer")).andExpect(status().isOk());

        verify(customerService).findAll(PageRequest.of(0, 20, Sort.by("id")));
    }

    @Test
    void getAllCustomersClampsSizeToOneHundred() throws Exception {
        when(customerService.findAll(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/customer").param("page", "2").param("size", "500"))
                .andExpect(status().isOk());

        verify(customerService).findAll(PageRequest.of(2, 100, Sort.by("id")));
    }

    @Test
    void getAllCustomersWithNegativePageReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("page"));

        verify(customerService, never()).findAll(any());
    }

    @Test
    void getAllCustomersWithZeroSizeReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        verify(customerService, never()).findAll(any());
    }

    @Test
    void getAllCustomersWithNonNumericPageReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verify(customerService, never()).findAll(any());
    }

    @Test
    void searchCustomersByNameReturnsPlainArrayWithPagingLinksThatKeepTheName() throws Exception {
        when(customerService.search(eq("doe"), any()))
                .thenReturn(new PageImpl<>(List.of(customer(1L, "John Doe")), Pagination.pageRequest(0, 1), 2));

        // In the URL rather than via param(): MockMvc only fills the query string, which the links are built from,
        // then.
        mockMvc.perform(get("/customer?name=doe&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("John Doe"))
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(header().string(
                                "Link",
                                "<http://localhost/customer?name=doe&page=0&size=1>; rel=\"first\", "
                                        + "<http://localhost/customer?name=doe&page=1&size=1>; rel=\"next\", "
                                        + "<http://localhost/customer?name=doe&page=1&size=1>; rel=\"last\""));

        verify(customerService).search("doe", PageRequest.of(0, 1, Sort.by("id")));
        verify(customerService, never()).findAll(any());
    }

    @Test
    void searchCustomersClampsSizeToOneHundred() throws Exception {
        when(customerService.search(anyString(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/customer").param("name", "doe").param("page", "3").param("size", "500"))
                .andExpect(status().isOk());

        verify(customerService).search("doe", PageRequest.of(3, 100, Sort.by("id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void searchCustomersWithBlankNameReturns400(String name) throws Exception {
        mockMvc.perform(get("/customer").param("name", name))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        verifyNoInteractions(customerService);
    }

    @Test
    void searchCustomersWithTooLongNameReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("name", "x".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        verifyNoInteractions(customerService);
    }

    @Test
    void searchCustomersAcceptsNameOfMaximumLength() throws Exception {
        when(customerService.search(anyString(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/customer").param("name", "x".repeat(255))).andExpect(status().isOk());
    }

    @Test
    void searchCustomersWithInvalidPagingReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("name", "doe").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));

        verifyNoInteractions(customerService);
    }

    @Test
    void createCustomerReturns201() throws Exception {
        when(customerService.create(new CreateCustomerRequest("John Doe"))).thenReturn(customer(101L, "John Doe"));

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"John Doe\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.orders", hasSize(0)));
    }

    @Test
    void createCustomerIgnoresClientSuppliedIdAndOrders() throws Exception {
        when(customerService.create(new CreateCustomerRequest("John Doe"))).thenReturn(customer(101L, "John Doe"));

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":5,\"name\":\"John Doe\",\"orders\":[{\"id\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101));

        verify(customerService).create(new CreateCustomerRequest("John Doe"));
    }

    @Test
    void createCustomerWithBlankNameReturns400() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        verify(customerService, never()).create(any());
    }

    @Test
    void createCustomerWithMissingNameReturns400() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void createCustomerWithTooLongNameReturns400() throws Exception {
        String name = "x".repeat(256);

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void createCustomerWithMalformedJsonReturns400WithoutParserDetails() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request body is not readable."))
                .andExpect(content().string(not(containsString("jackson"))));
    }

    @Test
    void dataIntegrityViolationReturns409WithoutSql() throws Exception {
        when(customerService.create(any()))
                .thenThrow(new DataIntegrityViolationException("ERROR: duplicate key value violates constraint"));

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"John Doe\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("duplicate key"))));
    }

    @Test
    void unexpectedExceptionReturnsGeneric500() throws Exception {
        when(customerService.findAll(any())).thenThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(get("/customer"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("secret internals"))));
    }

    @Test
    void unknownUrlReturns404NotCaughtByCatchAll() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unsupportedMethodReturns405NotCaughtByCatchAll() throws Exception {
        mockMvc.perform(delete("/customer"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unsupportedMediaTypeReturns415NotCaughtByCatchAll() throws Exception {
        mockMvc.perform(post("/customer").contentType(MediaType.TEXT_PLAIN).content("John Doe"))
                .andExpect(status().isUnsupportedMediaType());
    }

    private static CustomerDTO customer(Long id, String name, CustomerOrderDTO... orders) {
        CustomerDTO dto = new CustomerDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setOrders(List.of(orders));
        return dto;
    }

    private static CustomerOrderDTO order(Long id, String description) {
        CustomerOrderDTO dto = new CustomerOrderDTO();
        dto.setId(id);
        dto.setDescription(description);
        return dto;
    }
}
