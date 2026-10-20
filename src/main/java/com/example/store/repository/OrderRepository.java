package com.example.store.repository;

import com.example.store.entity.Order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * One query for the page with each order's customer join-fetched (plus the count query), instead of one extra query
     * per batch of customers. Safe to combine with paging because customer is a to-one association: the join does not
     * multiply rows, so LIMIT/OFFSET still happen in the database.
     */
    @Override
    @EntityGraph(attributePaths = "customer")
    Page<Order> findAll(Pageable pageable);

    /** One order with its customer join-fetched: a single statement, rather than findById plus a lazy load. */
    @EntityGraph(attributePaths = "customer")
    Optional<Order> findWithCustomerById(Long id);

    /**
     * Orders of several customers in one query, sorted by id, used to fill a page of customers. Filters on the
     * customer_id foreign key column (served by idx_order_customer_id) without joining the customer table: an explicit
     * query, because the equivalent derived query adds an inner join on customer.
     */
    @Query("select o from Order o where o.customer.id in :customerIds order by o.id")
    List<Order> findByCustomerIdIn(@Param("customerIds") Collection<Long> customerIds);
}
