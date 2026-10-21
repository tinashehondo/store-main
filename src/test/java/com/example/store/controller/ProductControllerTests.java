package com.example.store.controller;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.ProductService;

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

import static com.example.store.controller.ProblemAssertions.noInternals;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void getAllProductsReturnsPlainArrayWithOrderIdsAndPagingHeaders() throws Exception {
        when(productService.findAll(any()))
                .thenReturn(new PageImpl<>(
                        List.of(product(1L, "Widget", 10001L, 10002L)), Pagination.pageRequest(1, 1), 3));

        mockMvc.perform(get("/products").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].description").value("Widget"))
                .andExpect(jsonPath("$[0].orders", contains(10001, 10002)))
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(header().string(
                                "Link",
                                "<http://localhost/products?page=0&size=1>; rel=\"first\", "
                                        + "<http://localhost/products?page=0&size=1>; rel=\"prev\", "
                                        + "<http://localhost/products?page=2&size=1>; rel=\"next\", "
                                        + "<http://localhost/products?page=2&size=1>; rel=\"last\""));

        verify(productService).findAll(PageRequest.of(1, 1, Sort.by("id")));
    }

    @Test
    void getAllProductsDefaultsToFirstPageOfTwentyAndClampsSize() throws Exception {
        when(productService.findAll(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/products")).andExpect(status().isOk());
        mockMvc.perform(get("/products").param("size", "500")).andExpect(status().isOk());

        verify(productService).findAll(PageRequest.of(0, 20, Sort.by("id")));
        verify(productService).findAll(PageRequest.of(0, 100, Sort.by("id")));
    }

    @Test
    void getAllProductsWithInvalidPagingReturns400() throws Exception {
        mockMvc.perform(get("/products").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        mockMvc.perform(get("/products").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        verifyNoInteractions(productService);
    }

    @Test
    void getAllProductsWithPageWhoseOffsetOverflowsReturns400() throws Exception {
        mockMvc.perform(get("/products")
                        .param("page", String.valueOf(Integer.MAX_VALUE))
                        .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("page"));

        verifyNoInteractions(productService);
    }

    @Test
    void getProductByIdReturnsItWithItsOrderIds() throws Exception {
        when(productService.findById(1L)).thenReturn(product(1L, "Widget", 10001L));

        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("Widget"))
                .andExpect(jsonPath("$.orders", contains(10001)));
    }

    @Test
    void getProductByUnknownIdReturns404() throws Exception {
        when(productService.findById(999L)).thenThrow(new ResourceNotFoundException("Product", 999L));

        mockMvc.perform(get("/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product with id 999 not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    void getProductByNonNumericOrOverflowingIdReturns400(String id) throws Exception {
        mockMvc.perform(get("/products/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(productService);
    }

    @Test
    void getProductByBlankIdReturns400WithAMatchingBody() throws Exception {
        mockMvc.perform(get("/products/{id}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(productService);
    }

    @Test
    void createProductReturns201WithNoOrders() throws Exception {
        when(productService.create(new CreateProductRequest("Widget"))).thenReturn(product(1L, "Widget"));

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Widget\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("Widget"))
                .andExpect(jsonPath("$.orders", hasSize(0)));
    }

    @Test
    void createProductIgnoresClientSuppliedIdAndOrders() throws Exception {
        when(productService.create(new CreateProductRequest("Widget"))).thenReturn(product(1L, "Widget"));

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":5,\"description\":\"Widget\",\"orders\":[1]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        verify(productService).create(new CreateProductRequest("Widget"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"description\":null}", "{\"description\":\"  \"}"})
    void createProductWithoutDescriptionReturns400(String body) throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("description"));

        verify(productService, never()).create(any());
    }

    @Test
    void createProductWithTooLongDescriptionReturns400() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"));

        verify(productService, never()).create(any());
    }

    @Test
    void createProductWithNulInDescriptionReturns400() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Wid\\u0000get\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("must not contain NUL characters"));

        verify(productService, never()).create(any());
    }

    @Test
    void createProductWithDuplicateKeyReturns400() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"a\",\"description\":\"b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request body is not readable."));

        verify(productService, never()).create(any());
    }

    @Test
    void createProductWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request body is not readable."));
    }

    @Test
    void unsupportedMethodReturns405WithoutInternals() throws Exception {
        mockMvc.perform(delete("/products/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(noInternals());

        verifyNoInteractions(productService);
    }

    @Test
    void createProductWithUnsupportedMediaTypeReturns415WithoutInternals() throws Exception {
        mockMvc.perform(post("/products").contentType(MediaType.TEXT_PLAIN).content("Widget"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(noInternals());

        verifyNoInteractions(productService);
    }

    private static ProductDTO product(Long id, String description, Long... orderIds) {
        ProductDTO dto = new ProductDTO();
        dto.setId(id);
        dto.setDescription(description);
        dto.setOrders(List.of(orderIds));
        return dto;
    }
}
