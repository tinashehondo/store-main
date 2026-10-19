package com.example.store.integration;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for integration tests against a real PostgreSQL, migrated by Liquibase including the {@code demo} seed
 * data.
 *
 * <p>The container is a singleton: it is started once in a static initialiser (not managed by {@code @Container}, which
 * would stop it after each test class) and shared by every subclass; Testcontainers removes it when the JVM exits.
 * Because all subclasses share the same configuration, the Spring context is cached and reused too. Tests write to this
 * shared database, so assertions must not depend on row counts or on test ordering.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.2");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    /** Names of the top-level fields of a JSON object, used to assert the exact response shape. */
    protected static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
