package com.example.store.controller;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.CustomerOrderDTO;
import com.example.store.service.CustomerService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void getAllCustomersReturnsCustomersWithTheirOrders() throws Exception {
        when(customerService.findAll()).thenReturn(List.of(customer(1L, "John Doe", order(7L, "Test Order"))));

        mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("John Doe"))
                .andExpect(jsonPath("$[0].orders[0].id").value(7))
                .andExpect(jsonPath("$[0].orders[0].description").value("Test Order"));
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
        when(customerService.findAll()).thenThrow(new IllegalStateException("secret internals"));

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
