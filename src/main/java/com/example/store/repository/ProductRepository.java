package com.example.store.repository;

import com.example.store.entity.Product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * The two join-table queries are native on purpose: they read order_product directly, whereas JPQL can only reach it
 * through Order#products and would add a join on "order" that nothing needs. The aliases are quoted because PostgreSQL
 * folds unquoted ones to lower case, and the projections are matched by name.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Ids of the orders containing each of {@code productIds}, sorted by product then order id: exactly the order of
     * idx_order_product_product_id, so this is an index-only scan with no sort. Never touches the order table.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT product_id AS "productId", order_id AS "orderId"
                    FROM order_product
                    WHERE product_id IN (:productIds)
                    ORDER BY product_id, order_id
                    """)
    List<ProductOrderId> findOrderIdsByProductIdIn(@Param("productIds") Collection<Long> productIds);

    /**
     * The products of several orders in one query, used to fill a page of orders. Served by the order_product primary
     * key, whose leading column is order_id; sorted by order then product id.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT op.order_id AS "orderId", p.id AS "id", p.description AS "description"
                    FROM order_product op
                    JOIN product p ON p.id = op.product_id
                    WHERE op.order_id IN (:orderIds)
                    ORDER BY op.order_id, op.product_id
                    """)
    List<OrderProduct> findByOrderIdIn(@Param("orderIds") Collection<Long> orderIds);

    /** One row of order_product: a product and an order that contains it. */
    interface ProductOrderId {
        Long getProductId();

        Long getOrderId();
    }

    /** A product of an order, with the id of that order. */
    interface OrderProduct {
        Long getOrderId();

        Long getId();

        String getDescription();
    }
}
