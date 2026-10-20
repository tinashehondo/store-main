package com.example.store.service;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.entity.Product;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.ProductMapper;
import com.example.store.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    /**
     * At most three statements whatever the page size: the page of products, the count, and the order ids of all of
     * them in one {@code product_id IN (...)} query on the join table. Mapping happens inside the transaction as
     * open-in-view is disabled.
     */
    public Page<ProductDTO> findAll(Pageable pageable) {
        Page<Product> products = productRepository.findAll(pageable);
        if (products.isEmpty()) {
            return products.map(product -> productMapper.productToProductDTO(product, List.of()));
        }

        Map<Long, List<Long>> orderIds =
                orderIdsByProductId(products.map(Product::getId).getContent());
        return products.map(product ->
                productMapper.productToProductDTO(product, orderIds.getOrDefault(product.getId(), List.of())));
    }

    /** Two statements: the product, then the ids of the orders that contain it. */
    public ProductDTO findById(Long id) {
        Product product =
                productRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", id));
        return productMapper.productToProductDTO(
                product, orderIdsByProductId(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional
    public ProductDTO create(CreateProductRequest request) {
        Product product = new Product();
        product.setDescription(request.description());
        // A new product is in no order yet.
        return productMapper.productToProductDTO(productRepository.save(product), List.of());
    }

    // groupingBy keeps encounter order, so each product's order ids stay sorted.
    private Map<Long, List<Long>> orderIdsByProductId(Collection<Long> productIds) {
        return productRepository.findOrderIdsByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(
                        ProductRepository.ProductOrderId::getProductId,
                        Collectors.mapping(ProductRepository.ProductOrderId::getOrderId, Collectors.toList())));
    }
}
