package com.example.store.integration;

import com.example.store.repository.OrderRepository;
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

class OrderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void createOrderOnSeededDatabaseResolvesCustomerAndGetsIdAfterSeedData() throws Exception {
        String response = mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Integration Order\",\"customerId\":58}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        // The seed data uses ids 1..10000; anything else means the sequence was not advanced.
        assertThat(created.get("id").asLong()).isGreaterThan(10_000L);
        assertThat(created.get("description").asText()).isEqualTo("Integration Order");
        assertThat(created.get("customer").get("id").asLong()).isEqualTo(58L);
        assertThat(created.get("customer").get("name").asText()).isEqualTo("Heidi Becker");
    }

    @Test
    void createOrderWithUnknownCustomerReturns422() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Integration Order\",\"customerId\":999999}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Customer with id 999999 does not exist"));
    }

    @Test
    void getOrdersKeepsTheExistingJsonShape() throws Exception {
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
        assertThat(fieldNames(order)).containsExactlyInAnyOrder("id", "description", "customer");
        assertThat(order.get("description").asText()).isEqualTo("Bespoke Frozen Gloves");

        JsonNode customer = order.get("customer");
        assertThat(fieldNames(customer)).containsExactlyInAnyOrder("id", "name");
        assertThat(customer.get("id").asLong()).isEqualTo(58L);
        assertThat(customer.get("name").asText()).isEqualTo("Heidi Becker");
    }

    @Test
    void getOrderByIdReturnsTheListShapeWithItsCustomer() throws Exception {
        JsonNode order = objectMapper.readTree(mockMvc.perform(get("/order/10000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(fieldNames(order)).containsExactlyInAnyOrder("id", "description", "customer");
        assertThat(order.get("id").asLong()).isEqualTo(10_000L);
        assertThat(order.get("description").asText()).isEqualTo("Bespoke Frozen Gloves");
        assertThat(fieldNames(order.get("customer"))).containsExactlyInAnyOrder("id", "name");
        assertThat(order.get("customer").get("id").asLong()).isEqualTo(58L);
        assertThat(order.get("customer").get("name").asText()).isEqualTo("Heidi Becker");
    }

    @Test
    void getOrderByIdUsesExactlyOneStatement() throws Exception {
        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> mockMvc.perform(get("/order/158")).andExpect(status().isOk()));

        // The order with its customer join-fetched; no second query for the lazy customer.
        assertThat(sql.statements()).isEqualTo(1);
        assertThat(sql.entitiesLoaded()).isEqualTo(2);
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
    void getOrderByNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/order/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void getOrdersUsesAtMostTwoStatementsWhateverThePageSize(int size) throws Exception {
        MvcResult[] result = new MvcResult[1];

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> result[0] = mockMvc.perform(get("/order").param("size", String.valueOf(size)))
                        .andExpect(status().isOk())
                        .andReturn());

        // Page of orders with customers join-fetched + count; no per-customer (batch) loading.
        assertThat(sql.statements()).isLessThanOrEqualTo(2);
        JsonNode orders = readArray(result[0].getResponse());
        assertThat(orders).hasSize(size);
        assertThat(orders.get(0).get("customer").get("name").asText()).isNotBlank();
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
