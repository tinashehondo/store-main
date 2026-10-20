package com.example.store.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

/**
 * A product that orders can contain. The association is owned by {@link Order#getProducts()}; deliberately not mapped
 * here, as a popular product can be in thousands of orders and only their ids are ever needed (read from the join table
 * by ProductRepository).
 */
@Entity
@Getter
@Setter
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String description;

    // See Customer#equals for the rationale.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Product other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Product(id=" + id + ", description=" + description + ")";
    }
}
