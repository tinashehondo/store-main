package com.example.store.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the application the way a deployment does, with no profile and no {@code LIQUIBASE_CONTEXTS}, against its own
 * empty database (not the shared, seeded one of {@link AbstractIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionDefaultsIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.2");

    static {
        POSTGRES.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void freshDatabaseGetsTheSchemaWithoutDemoDataAndIdsStartAtOne() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customer", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM \"order\"", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForList("SELECT id FROM databasechangelog", String.class))
                .doesNotContain("2-data");

        String response = mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"First Customer\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        assertThat(created.get("id").asLong()).isEqualTo(1L);
    }
}
