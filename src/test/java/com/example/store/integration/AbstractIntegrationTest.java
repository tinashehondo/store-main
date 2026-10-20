package com.example.store.integration;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.2");

    static {
        POSTGRES.start();
    }

    private static final Pattern LINK = Pattern.compile("<([^>]*)>;\\s*rel=\"([^\"]+)\"");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    protected SqlStatementCounter sqlStatementCounter() {
        return new SqlStatementCounter(entityManagerFactory);
    }

    /** Names of the top-level fields of a JSON object, used to assert the exact response shape. */
    protected static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Parses an RFC 8288 {@code Link} header into rel to URI, in header order. */
    protected static Map<String, String> links(String linkHeader) {
        Map<String, String> links = new LinkedHashMap<>();
        Matcher matcher = LINK.matcher(linkHeader);
        while (matcher.find()) {
            links.put(matcher.group(2), matcher.group(1));
        }
        return links;
    }
}
