package com.example.store.controller;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.OrderProductDTO;
import com.example.store.exception.ReferencedResourceNotFoundException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.OrderService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Set;
import java.util.stream.LongStream;

import static com.example.store.controller.ProblemAssertions.noInternals;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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
                .andExpect(jsonPath("$[0].products[0].id").value(3))
                .andExpect(jsonPath("$[0].products[0].description").value("Widget"))
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
    void getAllOrdersWithPageWhoseOffsetOverflowsReturns400() throws Exception {
        mockMvc.perform(get("/order")
                        .param("page", String.valueOf(Integer.MAX_VALUE))
                        .param("size", "100"))
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
    void getOrderByIdReturnsOrderWithItsCustomerAndProducts() throws Exception {
        when(orderService.findById(11L)).thenReturn(order(11L, "Test Order", 1L, "John Doe"));

        mockMvc.perform(get("/order/11"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.description").value("Test Order"))
                .andExpect(jsonPath("$.customer.id").value(1))
                .andExpect(jsonPath("$.customer.name").value("John Doe"))
                .andExpect(jsonPath("$.products", hasSize(1)))
                .andExpect(jsonPath("$.products[0].id").value(3));
    }

    @Test
    void getOrderByUnknownIdReturns404() throws Exception {
        when(orderService.findById(999L)).thenThrow(new ResourceNotFoundException("Order", 999L));

        mockMvc.perform(get("/order/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order with id 999 not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    void getOrderByNonNumericOrOverflowingIdReturns400(String id) throws Exception {
        mockMvc.perform(get("/order/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verify(orderService, never()).findById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\t"})
    void getOrderByBlankIdReturns400WithAMatchingBody(String id) throws Exception {
        // The id converts to null; Spring's default body for that says 500 although the response is a 400.
        mockMvc.perform(get("/order/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Required path variable 'id' is not present."));

        verify(orderService, never()).findById(any());
    }

    @Test
    void createOrderReturns201WithResolvedCustomerAndProducts() throws Exception {
        when(orderService.create(new CreateOrderRequest("Test Order", 1L, Set.of(3L))))
                .thenReturn(order(10001L, "Test Order", 1L, "John Doe"));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[3]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10001))
                .andExpect(jsonPath("$.description").value("Test Order"))
                .andExpect(jsonPath("$.customer.id").value(1))
                .andExpect(jsonPath("$.customer.name").value("John Doe"))
                .andExpect(jsonPath("$.products[0].id").value(3))
                .andExpect(jsonPath("$.products[0].description").value("Widget"));
    }

    @Test
    void createOrderCollapsesDuplicateProductIds() throws Exception {
        when(orderService.create(any())).thenReturn(order(10001L, "Test Order", 1L, "John Doe"));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[3,3,4]}"))
                .andExpect(status().isCreated());

        verify(orderService).create(new CreateOrderRequest("Test Order", 1L, Set.of(3L, 4L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"productIds\":null", ",\"productIds\":[]"})
    void createOrderWithoutProductsReturns400(String productIds) throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1" + productIds + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));

        verify(orderService, never()).create(any());
    }

    @Test
    void createOrderWithNullProductIdReturns400() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[3,null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(containsString("productIds")));

        verify(orderService, never()).create(any());
    }

    @Test
    void createOrderWithMoreThanOneHundredProductsReturns400() throws Exception {
        String ids = String.join(
                ",", LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).toList());

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));

        verify(orderService, never()).create(any());
    }

    @Test
    void createOrderWithUnknownProductsReturns422ListingThem() throws Exception {
        when(orderService.create(any())).thenThrow(new ReferencedResourceNotFoundException("Product", List.of(7L, 9L)));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[3,7,9]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Product ids [7, 9] do not exist"));
    }

    @Test
    void createOrderWithUnknownCustomerReturns422() throws Exception {
        when(orderService.create(any())).thenThrow(new ReferencedResourceNotFoundException("Customer", 999L));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":999,\"productIds\":[3]}"))
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
                .andExpect(
                        jsonPath("$.errors[*].field", containsInAnyOrder("description", "customerId", "productIds")));

        verify(orderService, never()).create(any());
    }

    @Test
    void createOrderWithLegacyNestedCustomerBodyReturns400() throws Exception {
        // The pre-refactoring contract; customerId is now required instead.
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customer\":{\"id\":1},\"productIds\":[3]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("customerId")));
    }

    @Test
    void createOrderWithTooLongDescriptionReturns400() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + "x".repeat(256) + "\",\"customerId\":1,\"productIds\":[3]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"));
    }

    @Test
    void createOrderWithNulInDescriptionReturns400() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test\\u0000Order\",\"customerId\":1,\"productIds\":[3]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("must not contain NUL characters"));

        verify(orderService, never()).create(any());
    }

    @Test
    void createOrderWithDuplicateKeyReturns400() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"a\",\"description\":\"b\",\"customerId\":1,\"productIds\":[3]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is not readable."));

        verify(orderService, never()).create(any());
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
    void unsupportedMethodReturns405WithoutInternals() throws Exception {
        mockMvc.perform(put("/order"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(noInternals());
    }

    @Test
    void createOrderWithUnsupportedMediaTypeReturns415WithoutInternals() throws Exception {
        mockMvc.perform(post("/order").contentType(MediaType.TEXT_PLAIN).content("Test Order"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(noInternals());

        verify(orderService, never()).create(any());
    }

    private static OrderDTO order(Long id, String description, Long customerId, String customerName) {
        OrderCustomerDTO customer = new OrderCustomerDTO();
        customer.setId(customerId);
        customer.setName(customerName);

        OrderDTO dto = new OrderDTO();
        dto.setId(id);
        dto.setDescription(description);
        dto.setCustomer(customer);
        dto.setProducts(List.of(product(3L, "Widget")));
        return dto;
    }

    private static OrderProductDTO product(Long id, String description) {
        OrderProductDTO dto = new OrderProductDTO();
        dto.setId(id);
        dto.setDescription(description);
        return dto;
    }
}
