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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

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
        String response = mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode customers = objectMapper.readTree(response);
        assertThat(customers.isArray()).isTrue();

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
}
