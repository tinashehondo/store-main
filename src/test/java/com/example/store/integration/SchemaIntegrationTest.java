package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    void customerNameTrigramIndexExistsOnTheSearchedExpressionAndIsValid() {
        Boolean valid = jdbcTemplate.queryForObject(
                """
                SELECT i.indisvalid
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'idx_customer_name_trgm'
                  AND i.indrelid = 'customer'::regclass
                  AND pg_get_indexdef(i.indexrelid) LIKE '%USING gin (lower((name)::text) gin_trgm_ops)'
                """,
                Boolean.class);

        assertThat(valid).isTrue();
    }

    /**
     * The trigram index is usable for the search predicate exactly as CustomerRepository#findByNameLike writes it:
     * lower(name) on the left, an escaped pattern with a leading wildcard on the right. If the index expression and the
     * query expression ever drift apart, this fails.
     *
     * <p>It explains the count query of the search, whose plan depends on the predicate alone. With 100 seed rows a
     * sequential scan is cheaper, so it is disabled for this transaction only: this proves the index can be used, not
     * that it is chosen on a tiny table. The page query is deliberately not asserted: with ORDER BY id LIMIT n the
     * planner may legitimately walk customer_pkey instead (see PERFORMANCE.md).
     */
    @Test
    void customerNameSearchCanUseTheTrigramIndex() {
        List<String> plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList(
                    "EXPLAIN SELECT count(c.id) FROM customer c WHERE lower(c.name) LIKE lower(?) ESCAPE '\\'",
                    String.class,
                    "%onnel%");
        });

        assertThat(String.join("\n", plan)).contains("Bitmap Index Scan on idx_customer_name_trgm");
    }
}
