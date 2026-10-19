package com.example.store.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

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
        String response = mockMvc.perform(get("/order"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode orders = objectMapper.readTree(response);
        assertThat(orders.isArray()).isTrue();

        JsonNode order = StreamSupport.stream(orders.spliterator(), false)
                .filter(node -> node.get("id").asLong() == 10_000L)
                .findFirst()
                .orElseThrow();
        assertThat(order.properties()).extracting("key").containsExactlyInAnyOrder("id", "description", "customer");
        assertThat(order.get("description").asText()).isEqualTo("Bespoke Frozen Gloves");

        JsonNode customer = order.get("customer");
        assertThat(customer.properties()).extracting("key").containsExactlyInAnyOrder("id", "name");
        assertThat(customer.get("id").asLong()).isEqualTo(58L);
        assertThat(customer.get("name").asText()).isEqualTo("Heidi Becker");
    }
}
