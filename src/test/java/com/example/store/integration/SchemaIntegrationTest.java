package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void orderCustomerIdIsIndexedAndTheIndexIsValid() {
        // indisvalid is false when a CREATE INDEX CONCURRENTLY failed halfway; such an index is never used.
        Boolean valid = jdbcTemplate.queryForObject(
                """
                SELECT i.indisvalid
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'idx_order_customer_id'
                  AND i.indrelid = '"order"'::regclass
                  AND pg_get_indexdef(i.indexrelid) LIKE '%(customer_id)'
                """,
                Boolean.class);

        assertThat(valid).isTrue();
    }
}
