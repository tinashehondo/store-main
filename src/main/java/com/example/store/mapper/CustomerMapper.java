package com.example.store.mapper;

import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerMapper {
    CustomerDTO customerToCustomerDTO(Customer customer);

    /**
     * Maps a customer with orders that were loaded separately, so the lazy {@code customer.orders} collection is never
     * touched (and never triggers a query).
     */
    @Mapping(target = "orders", source = "orders")
    CustomerDTO customerToCustomerDTO(Customer customer, List<Order> orders);
}
