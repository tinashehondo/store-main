package com.example.store.controller;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.exception.ReferencedResourceNotFoundException;
import com.example.store.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
class OrderControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void getAllOrdersReturnsPlainArrayWithPagingHeaders() throws Exception {
        when(orderService.findAll(any()))
                .thenReturn(new PageImpl<>(
                        List.of(order(11L, "Test Order", 1L, "John Doe")), Pagination.pageRequest(1, 10), 25));

        mockMvc.perform(get("/order").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].description").value("Test Order"))
                .andExpect(jsonPath("$[0].customer.id").value(1))
                .andExpect(jsonPath("$[0].customer.name").value("John Doe"))
                .andExpect(header().string("X-Total-Count", "25"))
                .andExpect(header().string(
                                "Link",
                                "<http://localhost/order?page=0&size=10>; rel=\"first\", "
                                        + "<http://localhost/order?page=0&size=10>; rel=\"prev\", "
                                        + "<http://localhost/order?page=2&size=10>; rel=\"next\", "
                                        + "<http://localhost/order?page=2&size=10>; rel=\"last\""));

        verify(orderService).findAll(PageRequest.of(1, 10, Sort.by("id")));
    }

    @Test
    void getAllOrdersClampsSizeToOneHundred() throws Exception {
        when(orderService.findAll(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/order").param("size", "101")).andExpect(status().isOk());

        verify(orderService).findAll(PageRequest.of(0, 100, Sort.by("id")));
    }

    @Test
    void getAllOrdersWithNegativePageReturns400() throws Exception {
        mockMvc.perform(get("/order").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("page"));

        verify(orderService, never()).findAll(any());
    }

    @Test
    void getAllOrdersWithNegativeSizeReturns400() throws Exception {
        mockMvc.perform(get("/order").param("size", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        verify(orderService, never()).findAll(any());
    }

    @Test
    void createOrderReturns201WithResolvedCustomer() throws Exception {
        when(orderService.create(new CreateOrderRequest("Test Order", 1L)))
                .thenReturn(order(10001L, "Test Order", 1L, "John Doe"));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10001))
                .andExpect(jsonPath("$.description").value("Test Order"))
                .andExpect(jsonPath("$.customer.id").value(1))
                .andExpect(jsonPath("$.customer.name").value("John Doe"));
    }

    @Test
    void createOrderWithUnknownCustomerReturns422() throws Exception {
        when(orderService.create(any())).thenThrow(new ReferencedResourceNotFoundException("Customer", 999L));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":999}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Customer with id 999 does not exist"));
    }

    @Test
    void createOrderWithMissingFieldsReturns400WithAllFieldErrors() throws Exception {
        mockMvc.perform(post("/order").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("description", "customerId")));

        verify(orderService, never()).create(any());
    }

    @Test
    void createOrderWithLegacyNestedCustomerBodyReturns400() throws Exception {
        // The pre-refactoring contract; customerId is now required instead.
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customer\":{\"id\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"));
    }

    @Test
    void createOrderWithTooLongDescriptionReturns400() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + "x".repeat(256) + "\",\"customerId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"));
    }

    @Test
    void createOrderWithNonNumericCustomerIdReturns400() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createOrderWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/order").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request body is not readable."));
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(put("/order")).andExpect(status().isMethodNotAllowed());
    }

    private static OrderDTO order(Long id, String description, Long customerId, String customerName) {
        OrderCustomerDTO customer = new OrderCustomerDTO();
        customer.setId(customerId);
        customer.setName(customerName);

        OrderDTO dto = new OrderDTO();
        dto.setId(id);
        dto.setDescription(description);
        dto.setCustomer(customer);
        return dto;
    }
}
