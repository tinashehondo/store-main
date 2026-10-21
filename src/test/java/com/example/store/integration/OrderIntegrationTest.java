package com.example.store.integration;

import com.example.store.entity.Product;
import com.example.store.repository.OrderRepository;
import com.example.store.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createOrderOnSeededDatabaseResolvesCustomerAndProductsAndGetsIdAfterSeedData() throws Exception {
        Product gadget = saveProduct("Gadget");
        Product widget = saveProduct("Widget");

        // Listed out of order and with a duplicate: the response has each product once, sorted by id.
        JsonNode created = createOrder(58L, widget.getId(), gadget.getId(), widget.getId());

        // The seed data uses ids 1..10000; anything else means the sequence was not advanced.
        assertThat(created.get("id").asLong()).isGreaterThan(10_000L);
        assertThat(fieldNames(created)).containsExactlyInAnyOrder("id", "description", "customer", "products");
        assertThat(created.get("description").asText()).isEqualTo("Integration Order");
        assertThat(created.get("customer").get("id").asLong()).isEqualTo(58L);
        assertThat(created.get("customer").get("name").asText()).isEqualTo("Heidi Becker");
        assertThat(ids(created.get("products"))).containsExactly(gadget.getId(), widget.getId());
        assertThat(fieldNames(created.get("products").get(0))).containsExactlyInAnyOrder("id", "description");
        assertThat(created.get("products").get(0).get("description").asText()).isEqualTo("Gadget");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT product_id FROM order_product WHERE order_id = ? ORDER BY product_id",
                        Long.class,
                        created.get("id").asLong()))
                .containsExactly(gadget.getId(), widget.getId());
    }

    @Test
    void createOrderBatchesTheJoinTableInserts() throws Exception {
        Long[] productIds = {
            saveProduct("A").getId(),
            saveProduct("B").getId(),
            saveProduct("C").getId(),
            saveProduct("D").getId()
        };

        SqlStatementCounter.Result sql = sqlStatementCounter().measure(() -> createOrder(58L, productIds));

        // Customer + all products in one IN query + the order INSERT (IDENTITY, not batchable) + one JDBC batch for
        // the four order_product rows, rather than one INSERT each.
        assertThat(sql.statements()).isEqualTo(4);
    }

    @Test
    void createOrderWithUnknownProductsReturns422ListingThemAndPersistsNothing() throws Exception {
        Long known = saveProduct("Known").getId();
        long ordersBefore = orderRepository.count();

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Integration Order\",\"customerId\":58,\"productIds\":[" + known
                                + ",999999999,999999998]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Product ids [999999998, 999999999] do not exist"));

        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
    }

    @Test
    void createOrderWithoutProductsReturns400() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Integration Order\",\"customerId\":58,\"productIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));
    }

    @Test
    void createOrderWithUnknownCustomerReturns422() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Integration Order\",\"customerId\":999999,\"productIds\":[1]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Customer with id 999999 does not exist"));
    }

    @Test
    void getOrdersKeepsTheExistingJsonShapeAndLegacyOrdersHaveNoProducts() throws Exception {
        // Ids 9901..10000 of the seed data; orders created by other tests have higher ids.
        JsonNode orders =
                readArray(mockMvc.perform(get("/order").param("page", "99").param("size", "100"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse());

        JsonNode order = StreamSupport.stream(orders.spliterator(), false)
                .filter(node -> node.get("id").asLong() == 10_000L)
                .findFirst()
                .orElseThrow();
        assertThat(fieldNames(order)).containsExactlyInAnyOrder("id", "description", "customer", "products");
        assertThat(order.get("description").asText()).isEqualTo("Bespoke Frozen Gloves");
        assertThat(order.get("products").isArray()).isTrue();
        assertThat(order.get("products")).isEmpty();

        JsonNode customer = order.get("customer");
        assertThat(fieldNames(customer)).containsExactlyInAnyOrder("id", "name");
        assertThat(customer.get("id").asLong()).isEqualTo(58L);
        assertThat(customer.get("name").asText()).isEqualTo("Heidi Becker");
    }

    @Test
    void getOrderByIdOfLegacyOrderReturnsTheListShapeWithItsCustomerAndNoProducts() throws Exception {
        JsonNode order = objectMapper.readTree(mockMvc.perform(get("/order/10000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(fieldNames(order)).containsExactlyInAnyOrder("id", "description", "customer", "products");
        assertThat(order.get("products")).isEmpty();
        assertThat(order.get("id").asLong()).isEqualTo(10_000L);
        assertThat(order.get("description").asText()).isEqualTo("Bespoke Frozen Gloves");
        assertThat(fieldNames(order.get("customer"))).containsExactlyInAnyOrder("id", "name");
        assertThat(order.get("customer").get("id").asLong()).isEqualTo(58L);
        assertThat(order.get("customer").get("name").asText()).isEqualTo("Heidi Becker");
    }

    @Test
    void getOrderByIdReturnsItsProducts() throws Exception {
        Product widget = saveProduct("Widget");
        Product gadget = saveProduct("Gadget");
        long orderId =
                createOrder(58L, gadget.getId(), widget.getId()).get("id").asLong();

        JsonNode order = objectMapper.readTree(mockMvc.perform(get("/order/{id}", orderId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(order.get("customer").get("name").asText()).isEqualTo("Heidi Becker");
        assertThat(ids(order.get("products"))).containsExactly(widget.getId(), gadget.getId());
        assertThat(order.get("products").get(0).get("description").asText()).isEqualTo("Widget");
    }

    @Test
    void getOrderByIdUsesAtMostTwoStatements() throws Exception {
        long orderId = createOrder(
                        58L, saveProduct("A").getId(), saveProduct("B").getId())
                .get("id")
                .asLong();

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> mockMvc.perform(get("/order/{id}", orderId)).andExpect(status().isOk()));

        // The order with its customer join-fetched, then its products; the lazy collection is never initialised.
        assertThat(sql.statements()).isLessThanOrEqualTo(2);
        assertThat(sql.entitiesLoaded()).isEqualTo(2);
        assertThat(sql.collectionFetches()).isZero();
    }

    @Test
    void getOrderByUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/order/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order with id 999999999 not found"));
    }

    @Test
    void getOrderByBlankIdReturns400WithAMatchingBody() throws Exception {
        mockMvc.perform(get("/order/%20"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getOrderByNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/order/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void getOrdersUsesAtMostThreeStatementsWhateverThePageSize(int size) throws Exception {
        MvcResult[] result = new MvcResult[1];

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> result[0] = mockMvc.perform(get("/order").param("size", String.valueOf(size)))
                        .andExpect(status().isOk())
                        .andReturn());

        // Page of orders with customers join-fetched + count + one IN query for all their products; no per-customer
        // (batch) loading and no lazy collection initialisation.
        assertThat(sql.statements()).isLessThanOrEqualTo(3);
        assertThat(sql.collectionFetches()).isZero();
        JsonNode orders = readArray(result[0].getResponse());
        assertThat(orders).hasSize(size);
        assertThat(orders.get(0).get("customer").get("name").asText()).isNotBlank();
        assertThat(orders.get(0).get("products").isArray()).isTrue();
    }

    @Test
    void getOrdersPageWithOrdersThatHaveProductsLoadsThemInAtMostThreeStatements() throws Exception {
        Product widget = saveProduct("Widget");
        Product gadget = saveProduct("Gadget");
        long orderId =
                createOrder(58L, widget.getId(), gadget.getId()).get("id").asLong();
        // The page that contains it (ids may have gaps, so count rather than divide the id).
        Long before = jdbcTemplate.queryForObject("SELECT count(*) FROM \"order\" WHERE id < ?", Long.class, orderId);
        long page = before / 100;
        MvcResult[] result = new MvcResult[1];

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> result[0] = mockMvc.perform(get("/order")
                                .param("page", String.valueOf(page))
                                .param("size", "100"))
                        .andExpect(status().isOk())
                        .andReturn());

        assertThat(sql.statements()).isLessThanOrEqualTo(3);
        assertThat(sql.collectionFetches()).isZero();
        JsonNode orders = readArray(result[0].getResponse());
        JsonNode order = StreamSupport.stream(orders.spliterator(), false)
                .filter(node -> node.get("id").asLong() == orderId)
                .findFirst()
                .orElseThrow();
        assertThat(ids(order.get("products"))).containsExactly(widget.getId(), gadget.getId());
        // The rest of the page (legacy orders, or orders of other tests) still has a products array.
        assertThat(orders)
                .allSatisfy(node -> assertThat(node.get("products").isArray()).isTrue());
    }

    @Test
    void getOrdersFirstPageHasNextAndLastButNoPrev() throws Exception {
        long total = orderRepository.count();

        MockHttpServletResponse response = mockMvc.perform(get("/order"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("X-Total-Count")).isEqualTo(String.valueOf(total));
        assertThat(links(response.getHeader("Link")))
                .containsExactly(
                        Map.entry("first", "http://localhost/order?page=0&size=20"),
                        Map.entry("next", "http://localhost/order?page=1&size=20"),
                        Map.entry("last", "http://localhost/order?page=" + (total - 1) / 20 + "&size=20"));
        assertThat(ids(readArray(response))).hasSize(20).isSorted().startsWith(1L);
    }

    @Test
    void getOrdersMiddlePageHasAllRelations() throws Exception {
        long last = (orderRepository.count() - 1) / 100;

        MockHttpServletResponse response = mockMvc.perform(
                        get("/order").param("page", "50").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(links(response.getHeader("Link")))
                .containsExactly(
                        Map.entry("first", "http://localhost/order?page=0&size=100"),
                        Map.entry("prev", "http://localhost/order?page=49&size=100"),
                        Map.entry("next", "http://localhost/order?page=51&size=100"),
                        Map.entry("last", "http://localhost/order?page=" + last + "&size=100"));
        assertThat(ids(readArray(response))).hasSize(100).isSorted().startsWith(5001L);
    }

    @Test
    void getOrdersLastPageHasPrevButNoNext() throws Exception {
        long total = orderRepository.count();
        long last = (total - 1) / 100;

        MockHttpServletResponse response = mockMvc.perform(
                        get("/order").param("page", String.valueOf(last)).param("size", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(links(response.getHeader("Link")))
                .containsExactly(
                        Map.entry("first", "http://localhost/order?page=0&size=100"),
                        Map.entry("prev", "http://localhost/order?page=" + (last - 1) + "&size=100"),
                        Map.entry("last", "http://localhost/order?page=" + last + "&size=100"));
        assertThat(readArray(response)).hasSize((int) (total - last * 100));
    }

    @Test
    void getOrdersBeyondTheLastPageReturnsEmptyArray() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/order").param("page", "100000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(readArray(response)).isEmpty();
        assertThat(links(response.getHeader("Link"))).containsOnlyKeys("first", "prev", "last");
    }

    @Test
    void getOrdersClampsSizeToOneHundred() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/order").param("size", "101"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(readArray(response)).hasSize(100);
        assertThat(links(response.getHeader("Link")).get("next")).endsWith("?page=1&size=100");
    }

    @Test
    void getOrdersWithInvalidPagingReturns400() throws Exception {
        mockMvc.perform(get("/order").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        mockMvc.perform(get("/order").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    private Product saveProduct(String description) {
        Product product = new Product();
        product.setDescription(description);
        return productRepository.save(product);
    }

    private JsonNode createOrder(long customerId, Long... productIds) throws Exception {
        String ids =
                String.join(",", Arrays.stream(productIds).map(String::valueOf).toList());
        return objectMapper.readTree(mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Integration Order\",\"customerId\":" + customerId
                                + ",\"productIds\":[" + ids + "]}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
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
}
