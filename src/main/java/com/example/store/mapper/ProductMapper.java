package com.example.store.mapper;

import com.example.store.dto.ProductDTO;
import com.example.store.entity.Product;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductMapper {

    /** Product does not map its orders; their ids are read from the join table and passed in. */
    @Mapping(target = "orders", source = "orderIds")
    ProductDTO productToProductDTO(Product product, List<Long> orderIds);
}
