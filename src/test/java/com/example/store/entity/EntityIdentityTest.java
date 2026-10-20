package com.example.store.entity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EntityIdentityTest {

    @Test
    void toStringAndHashCodeDoNotRecurseThroughBidirectionalAssociation() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setName("John Doe");
        Order order = new Order();
        order.setId(2L);
        order.setDescription("Test Order");
        order.setCustomer(customer);
        customer.getOrders().add(order);

        assertThat(customer.toString()).isEqualTo("Customer(id=1, name=John Doe)");
        assertThat(order.toString()).isEqualTo("Order(id=2, description=Test Order)");
        assertThat(customer.hashCode()).isEqualTo(Customer.class.hashCode());
        assertThat(order.hashCode()).isEqualTo(Order.class.hashCode());
    }

    @Test
    void entitiesWithSameIdAreEqual() {
        Customer a = new Customer();
        a.setId(1L);
        Customer b = new Customer();
        b.setId(1L);
        b.setName("different state");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void transientEntitiesAreNeverEqualToEachOther() {
        assertThat(new Order()).isNotEqualTo(new Order());
        assertThat(new Customer()).isNotEqualTo(new Customer());
        assertThat(new Product()).isNotEqualTo(new Product());
    }

    @Test
    void productToStringHashCodeAndEqualityFollowTheSameConventions() {
        Product product = new Product();
        product.setId(3L);
        product.setDescription("Widget");
        Product sameId = new Product();
        sameId.setId(3L);

        assertThat(product.toString()).isEqualTo("Product(id=3, description=Widget)");
        assertThat(product.hashCode()).isEqualTo(Product.class.hashCode());
        assertThat(product).isEqualTo(sameId);
    }

    @Test
    void orderToStringDoesNotIncludeProducts() {
        Product product = new Product();
        product.setId(3L);
        Order order = new Order();
        order.setId(2L);
        order.setDescription("Test Order");
        order.getProducts().add(product);

        assertThat(order.toString()).isEqualTo("Order(id=2, description=Test Order)");
    }

    @Test
    void entityStaysInHashSetAfterIdIsAssigned() {
        Order order = new Order();
        Set<Order> orders = new HashSet<>();
        orders.add(order);

        order.setId(10001L);

        assertThat(orders).contains(order);
    }

    @Test
    void differentEntityTypesWithSameIdAreNotEqual() {
        Customer customer = new Customer();
        customer.setId(1L);
        Order order = new Order();
        order.setId(1L);

        Product product = new Product();
        product.setId(1L);

        assertThat(customer).isNotEqualTo(order);
        assertThat(order).isNotEqualTo(product);
    }
}
