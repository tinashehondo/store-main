package com.example.store.mapper;

import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.OrderProductDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.entity.Product;
import com.example.store.repository.ProductRepository;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderMapper {

    /**
     * Maps an order with products that were loaded separately, so the lazy {@code order.products} collection is never
     * touched (and never triggers a query). There is deliberately no single-argument variant.
     */
    @Mapping(target = "products", source = "products")
    OrderDTO orderToOrderDTO(Order order, List<OrderProductDTO> products);

    OrderCustomerDTO orderToOrderCustomerDTO(Customer customer);

    OrderProductDTO productToOrderProductDTO(Product product);

    OrderProductDTO orderProductToOrderProductDTO(ProductRepository.OrderProduct orderProduct);
}
