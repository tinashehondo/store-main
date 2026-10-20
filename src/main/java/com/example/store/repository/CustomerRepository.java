package com.example.store.repository;

import com.example.store.entity.Customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Customers whose name matches a LIKE {@code pattern}, case-insensitively. Both sides are lowered by PostgreSQL, so
     * the comparison follows one set of case-folding rules, and {@code lower(name)} is exactly the expression of the
     * idx_customer_name_trgm trigram index (otherwise the planner cannot use it). The caller escapes wildcards with
     * {@code \}.
     */
    @Query(
            value = "select c from Customer c where lower(c.name) like lower(:pattern) escape '\\'",
            countQuery = "select count(c) from Customer c where lower(c.name) like lower(:pattern) escape '\\'")
    Page<Customer> findByNameLike(@Param("pattern") String pattern, Pageable pageable);
}
