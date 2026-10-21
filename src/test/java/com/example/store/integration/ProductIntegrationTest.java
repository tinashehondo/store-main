package com.example.store.integration;

import com.example.store.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void createProductReturns201WithNoOrders() throws Exception {
        JsonNode created = createProduct("Integration Product");

        assertThat(fieldNames(created)).containsExactlyInAnyOrder("id", "description", "orders");
        assertThat(created.get("id").asLong()).isPositive();
        assertThat(created.get("description").asText()).isEqualTo("Integration Product");
        assertThat(created.get("orders").isArray()).isTrue();
        assertThat(created.get("orders")).isEmpty();
    }

    @Test
    void productsAndOrdersReferToEachOtherEndToEnd() throws Exception {
        long widget = createProduct("Widget").get("id").asLong();
        long gadget = createProduct("Gadget").get("id").asLong();
        long both = createOrder(58L, widget, gadget).get("id").asLong();
        long widgetOnly = createOrder(1L, widget).get("id").asLong();

        JsonNode order = getJson("/order/" + both);
        assertThat(ids(order.get("products"))).containsExactly(widget, gadget);
        assertThat(order.get("products").get(1).get("description").asText()).isEqualTo("Gadget");

        JsonNode widgetProduct = getJson("/products/" + widget);
        assertThat(fieldNames(widgetProduct)).containsExactlyInAnyOrder("id", "description", "orders");
        assertThat(widgetProduct.get("description").asText()).isEqualTo("Widget");
        assertThat(longs(widgetProduct.get("orders"))).containsExactly(both, widgetOnly);
        assertThat(longs(getJson("/products/" + gadget).get("orders"))).containsExactly(both);

        // The same order ids in the list, on the page that contains the two products.
        JsonNode page = readArray(mockMvc.perform(get("/products")
                        .param("page", String.valueOf(pageOf(widget, 100)))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse());
        assertThat(longs(byId(page, widget).get("orders"))).containsExactly(both, widgetOnly);
        assertThat(longs(byId(page, gadget).get("orders"))).containsExactly(both);
    }

    @Test
    void getProductByUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/products/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product with id 999999999 not found"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void getProductByZeroOrNegativeIdReturns404(long id) throws Exception {
        mockMvc.perform(get("/products/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Product with id " + id + " not found"));
    }

    @Test
    void getProductByNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createProductWithBlankDescriptionReturns400() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("description"));
    }

    @Test
    void getProductsIsOrderedByIdAndExposesTotalCountAndLinks() throws Exception {
        for (int i = 0; i < 3; i++) {
            createProduct("Paged " + i);
        }
        long total = productRepository.count();
        long last = total - 1;

        MockHttpServletResponse response = mockMvc.perform(
                        get("/products").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("X-Total-Count")).isEqualTo(String.valueOf(total));
        assertThat(links(response.getHeader("Link")))
                .containsExactly(
                        Map.entry("first", "http://localhost/products?page=0&size=1"),
                        Map.entry("prev", "http://localhost/products?page=0&size=1"),
                        Map.entry("next", "http://localhost/products?page=2&size=1"),
                        Map.entry("last", "http://localhost/products?page=" + last + "&size=1"));
        assertThat(readArray(response)).hasSize(1);

        JsonNode firstPage = readArray(mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse());
        assertThat(ids(firstPage)).hasSizeBetween(3, 20).isSorted();
    }

    @Test
    void getProductsClampsSizeToOneHundred() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/products").param("size", "1000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(links(response.getHeader("Link")).get("first")).endsWith("?page=0&size=100");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void getProductsUsesAtMostThreeStatementsWhateverThePageSize(int size) throws Exception {
        long product = createProduct("Counted").get("id").asLong();
        createOrder(58L, product);
        createOrder(58L, product);
        // Outside measure(): it queries too.
        long page = pageOf(product, size);
        MvcResult[] result = new MvcResult[1];

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> result[0] = mockMvc.perform(get("/products")
                                .param("page", String.valueOf(page))
                                .param("size", String.valueOf(size)))
                        .andExpect(status().isOk())
                        .andReturn());

        // Page of products + count + one IN query on the join table for the order ids of all of them.
        assertThat(sql.statements()).isLessThanOrEqualTo(3);
        assertThat(sql.collectionFetches()).isZero();
        JsonNode products = readArray(result[0].getResponse());
        assertThat(products).hasSizeBetween(1, size);
        // Proves the order ids were actually loaded, not silently dropped.
        assertThat(byId(products, product).get("orders")).hasSize(2);
    }

    @Test
    void getProductByIdUsesAtMostTwoStatements() throws Exception {
        long product = createProduct("Counted").get("id").asLong();
        createOrder(58L, product);

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> mockMvc.perform(get("/products/{id}", product))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.orders.length()").value(1)));

        // The product, then its order ids from the join table; no order entity is loaded.
        assertThat(sql.statements()).isLessThanOrEqualTo(2);
        assertThat(sql.entitiesLoaded()).isEqualTo(1);
    }

    /** The page (of the given size, ordered by id) that contains the product: ids may have gaps, so count. */
    private long pageOf(long productId, int size) {
        return productRepository.findAll().stream()
                        .filter(p -> p.getId() < productId)
                        .count()
                / size;
    }

    private JsonNode createProduct(String description) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("description", description))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private JsonNode createOrder(long customerId, Long... productIds) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", "Product Order", "customerId", customerId, "productIds", productIds))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private JsonNode getJson(String path) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private static JsonNode byId(JsonNode array, long id) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(node -> node.get("id").asLong() == id)
                .findFirst()
                .orElseThrow();
    }

    private JsonNode readArray(MockHttpServletResponse response) throws Exception {
        JsonNode node = objectMapper.readTree(response.getContentAsString());
        assertThat(node.isArray()).isTrue();
        return node;
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asLong()));
        return values;
    }
}
