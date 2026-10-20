package com.example.store.service;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.entity.Product;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.ProductMapperImpl;
import com.example.store.repository.ProductRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, new ProductMapperImpl());
    }

    @Test
    void findAllLoadsOrderIdsOfThePageInOneQueryAndGroupsThemByProduct() {
        Pageable pageable = PageRequest.of(0, 3);
        when(productRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(
                        List.of(product(1L, "Widget"), product(2L, "Gadget"), product(3L, "Unsold")), pageable, 7));
        when(productRepository.findOrderIdsByProductIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(row(1L, 10L), row(1L, 12L), row(2L, 11L)));

        Page<ProductDTO> result = productService.findAll(pageable);

        assertThat(result.getTotalElements()).isEqualTo(7);
        assertThat(result.getContent()).extracting(ProductDTO::getId).containsExactly(1L, 2L, 3L);
        assertThat(result.getContent())
                .extracting(ProductDTO::getDescription)
                .containsExactly("Widget", "Gadget", "Unsold");
        assertThat(result.getContent().get(0).getOrders()).containsExactly(10L, 12L);
        assertThat(result.getContent().get(1).getOrders()).containsExactly(11L);
        assertThat(result.getContent().get(2).getOrders()).isEmpty();
    }

    @Test
    void findAllOnEmptyPageDoesNotQueryOrderIds() {
        Pageable pageable = PageRequest.of(9, 20);
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 3));

        Page<ProductDTO> result = productService.findAll(pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(3);
        verify(productRepository, never()).findOrderIdsByProductIdIn(any());
    }

    @Test
    void findByIdReturnsProductWithItsOrderIds() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "Widget")));
        when(productRepository.findOrderIdsByProductIdIn(List.of(1L))).thenReturn(List.of(row(1L, 10L), row(1L, 12L)));

        ProductDTO result = productService.findById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getDescription()).isEqualTo("Widget");
        assertThat(result.getOrders()).containsExactly(10L, 12L);
    }

    @Test
    void findByIdOfProductInNoOrderReturnsEmptyOrders() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "Widget")));
        when(productRepository.findOrderIdsByProductIdIn(List.of(1L))).thenReturn(List.of());

        assertThat(productService.findById(1L).getOrders()).isEmpty();
    }

    @Test
    void findByIdWithUnknownIdThrowsNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product with id 999 not found");
        verify(productRepository, never()).findOrderIdsByProductIdIn(any());
    }

    @Test
    void createPersistsNewProductFromRequest() {
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product toSave = invocation.getArgument(0);
            // Checked before the simulated id assignment: the entity must be new.
            assertThat(toSave.getId()).isNull();
            assertThat(toSave.getDescription()).isEqualTo("Widget");
            toSave.setId(1L);
            return toSave;
        });

        ProductDTO result = productService.create(new CreateProductRequest("Widget"));

        verify(productRepository).save(any(Product.class));
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getDescription()).isEqualTo("Widget");
        assertThat(result.getOrders()).isEmpty();
    }

    private static ProductRepository.ProductOrderId row(Long productId, Long orderId) {
        return new ProductRepository.ProductOrderId() {
            @Override
            public Long getProductId() {
                return productId;
            }

            @Override
            public Long getOrderId() {
                return orderId;
            }
        };
    }

    private static Product product(Long id, String description) {
        Product product = new Product();
        product.setId(id);
        product.setDescription(description);
        return product;
    }
}
