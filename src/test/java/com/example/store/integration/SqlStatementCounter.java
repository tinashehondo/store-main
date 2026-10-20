package com.example.store.integration;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Measures the SQL work done by one block of code through Hibernate {@link Statistics} (enabled in the {@code test}
 * profile only).
 *
 * <p>With a remote database every statement is at least one network round trip, so the statement count is the number
 * that dominates latency. Statistics are global to the session factory: this only gives meaningful numbers because
 * integration tests run sequentially.
 */
public final class SqlStatementCounter {

    private final Statistics statistics;

    public SqlStatementCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        if (!statistics.isStatisticsEnabled()) {
            throw new IllegalStateException("hibernate.generate_statistics must be enabled (test profile)");
        }
    }

    public Result measure(ThrowingRunnable action) throws Exception {
        statistics.clear();
        action.run();
        return new Result(
                statistics.getPrepareStatementCount(),
                statistics.getEntityLoadCount(),
                statistics.getCollectionFetchCount());
    }

    /**
     * @param statements JDBC statements prepared (each one a round trip to the database)
     * @param entitiesLoaded entity instances hydrated from result sets, a proxy for rows transferred
     * @param collectionFetches lazy collections initialised by a separate query
     */
    public record Result(long statements, long entitiesLoaded, long collectionFetches) {}

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
