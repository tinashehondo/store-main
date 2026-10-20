package com.example.store.integration;

import com.example.store.repository.CustomerRepository;
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

class CustomerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void createCustomerOnSeededDatabaseGetsIdAfterSeedData() throws Exception {
        String response = mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Integration Customer\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        // The seed data uses ids 1..100; anything else means the sequence was not advanced.
        assertThat(created.get("id").asLong()).isGreaterThan(100L);
        assertThat(created.get("name").asText()).isEqualTo("Integration Customer");
        assertThat(created.get("orders")).isEmpty();
    }

    @Test
    void getCustomersKeepsTheExistingJsonShape() throws Exception {
        JsonNode customers = readArray(mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse());

        JsonNode customer = StreamSupport.stream(customers.spliterator(), false)
                .filter(node -> node.get("id").asLong() == 1L)
                .findFirst()
                .orElseThrow();
        assertThat(fieldNames(customer)).containsExactlyInAnyOrder("id", "name", "orders");
        assertThat(customer.get("name").asText()).isEqualTo("Muriel Donnelly");

        JsonNode order = StreamSupport.stream(customer.get("orders").spliterator(), false)
                .filter(node -> node.get("id").asLong() == 158L)
                .findFirst()
                .orElseThrow();
        assertThat(fieldNames(order)).containsExactlyInAnyOrder("id", "description");
        assertThat(order.get("description").asText()).isEqualTo("Awesome Concrete Shirt");
    }

    @Test
    void getCustomersReturnsDefaultPageOfTwentyOrderedByIdWithOrdersOrderedById() throws Exception {
        JsonNode customers = readArray(mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse());

        assertThat(ids(customers)).hasSize(20).isSorted().startsWith(1L);
        for (JsonNode customer : customers) {
            assertThat(ids(customer.get("orders"))).isSorted();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void getCustomersUsesAtMostThreeStatementsWhateverThePageSize(int size) throws Exception {
        MvcResult[] result = new MvcResult[1];

        SqlStatementCounter.Result sql = sqlStatementCounter()
                .measure(() -> result[0] = mockMvc.perform(get("/customer").param("size", String.valueOf(size)))
                        .andExpect(status().isOk())
                        .andReturn());

        // Page of customers + count + one IN query for all their orders; no lazy collection initialisation.
        assertThat(sql.statements()).isLessThanOrEqualTo(3);
        assertThat(sql.collectionFetches()).isZero();
        JsonNode customers = readArray(result[0].getResponse());
        assertThat(customers).hasSize(size);
        // Every seeded customer has orders: proves they were actually loaded, not silently dropped.
        assertThat(customers.get(0).get("orders")).isNotEmpty();
    }

    @Test
    void getCustomersExposesTotalCountAndLinks() throws Exception {
        long total = customerRepository.count();
        long last = (total - 1) / 10;

        MockHttpServletResponse response = mockMvc.perform(
                        get("/customer").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("X-Total-Count")).isEqualTo(String.valueOf(total));
        assertThat(links(response.getHeader("Link")))
                .containsExactly(
                        Map.entry("first", "http://localhost/customer?page=0&size=10"),
                        Map.entry("prev", "http://localhost/customer?page=0&size=10"),
                        Map.entry("next", "http://localhost/customer?page=2&size=10"),
                        Map.entry("last", "http://localhost/customer?page=" + last + "&size=10"));
        assertThat(ids(readArray(response))).containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
    }

    @Test
    void getCustomersClampsSizeToOneHundred() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/customer").param("size", "1000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(readArray(response)).hasSize(100);
        assertThat(links(response.getHeader("Link")).get("first")).endsWith("?page=0&size=100");
    }

    @Test
    void getCustomersWithNegativePageReturns400() throws Exception {
        mockMvc.perform(get("/customer").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
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
