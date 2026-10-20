package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.OrderProductDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.entity.Product;
import com.example.store.exception.ReferencedResourceNotFoundException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.OrderMapperImpl;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;
import com.example.store.repository.ProductRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductRepository productRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, customerRepository, productRepository, new OrderMapperImpl());
    }

    @Test
    void findAllLoadsProductsOfThePageInOneQueryAndGroupsThemByOrder() {
        Customer john = customer(1L, "John Doe");
        Order first = order(1L, "First", john);
        Order second = order(2L, "Second", john);
        Order legacy = order(3L, "Legacy", john);
        Pageable pageable = PageRequest.of(0, 3);
        when(orderRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(first, second, legacy), pageable, 10));
        when(productRepository.findByOrderIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(
                        orderProduct(1L, 5L, "Widget"),
                        orderProduct(1L, 6L, "Gadget"),
                        orderProduct(2L, 5L, "Widget")));

        Page<OrderDTO> result = orderService.findAll(pageable);

        assertThat(result.getTotalElements()).isEqualTo(10);
        assertThat(result.getContent()).extracting(OrderDTO::getId).containsExactly(1L, 2L, 3L);
        assertThat(result.getContent().get(0).getCustomer().getName()).isEqualTo("John Doe");
        assertThat(result.getContent().get(0).getProducts())
                .extracting(OrderProductDTO::getId, OrderProductDTO::getDescription)
                .containsExactly(tuple(5L, "Widget"), tuple(6L, "Gadget"));
        assertThat(result.getContent().get(1).getProducts())
                .extracting(OrderProductDTO::getId)
                .containsExactly(5L);
        assertThat(result.getContent().get(2).getProducts()).isEmpty();
    }

    @Test
    void findAllOnEmptyPageDoesNotQueryProducts() {
        Pageable pageable = PageRequest.of(500, 20);
        when(orderRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 100));

        Page<OrderDTO> result = orderService.findAll(pageable);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(productRepository);
    }

    @Test
    void findByIdMapsOrderWithItsCustomerAndProducts() {
        when(orderRepository.findWithCustomerById(7L))
                .thenReturn(Optional.of(order(7L, "Test Order", customer(1L, "John Doe"))));
        when(productRepository.findByOrderIdIn(List.of(7L))).thenReturn(List.of(orderProduct(7L, 5L, "Widget")));

        OrderDTO result = orderService.findById(7L);

        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getDescription()).isEqualTo("Test Order");
        assertThat(result.getCustomer().getId()).isEqualTo(1L);
        assertThat(result.getCustomer().getName()).isEqualTo("John Doe");
        assertThat(result.getProducts())
                .extracting(OrderProductDTO::getId, OrderProductDTO::getDescription)
                .containsExactly(tuple(5L, "Widget"));
    }

    @Test
    void findByIdOfLegacyOrderReturnsEmptyProducts() {
        when(orderRepository.findWithCustomerById(7L))
                .thenReturn(Optional.of(order(7L, "Test Order", customer(1L, "John Doe"))));
        when(productRepository.findByOrderIdIn(List.of(7L))).thenReturn(List.of());

        assertThat(orderService.findById(7L).getProducts()).isEmpty();
    }

    @Test
    void findByIdWithUnknownIdThrowsNotFound() {
        when(orderRepository.findWithCustomerById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Order with id 999 not found");
        verifyNoInteractions(productRepository);
    }

    @Test
    void createResolvesCustomerAndProductsAndPersistsOrder() {
        Customer customer = customer(1L, "John Doe");
        Product widget = product(5L, "Widget");
        Product gadget = product(6L, "Gadget");
        Set<Long> productIds = new LinkedHashSet<>(List.of(6L, 5L));
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findAllById(productIds)).thenReturn(List.of(gadget, widget));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            saved.setId(10001L);
            return saved;
        });

        OrderDTO result = orderService.create(new CreateOrderRequest("Test Order", 1L, productIds));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("Test Order");
        assertThat(captor.getValue().getCustomer()).isSameAs(customer);
        assertThat(captor.getValue().getProducts()).containsExactlyInAnyOrder(widget, gadget);
        assertThat(result.getId()).isEqualTo(10001L);
        assertThat(result.getCustomer().getId()).isEqualTo(1L);
        assertThat(result.getCustomer().getName()).isEqualTo("John Doe");
        // Sorted by id, like the read paths, whatever the request or lookup order.
        assertThat(result.getProducts())
                .extracting(OrderProductDTO::getId, OrderProductDTO::getDescription)
                .containsExactly(tuple(5L, "Widget"), tuple(6L, "Gadget"));
    }

    @Test
    void createWithUnknownCustomerThrowsAndDoesNotPersist() {
        when(customerRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest("Test Order", 999L, Set.of(5L))))
                .isInstanceOf(ReferencedResourceNotFoundException.class)
                .hasMessage("Customer with id 999 does not exist");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void createWithUnknownProductsThrowsListingAllMissingIdsSortedAndDoesNotPersist() {
        Set<Long> productIds = new LinkedHashSet<>(List.of(9L, 5L, 7L));
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "John Doe")));
        when(productRepository.findAllById(productIds)).thenReturn(List.of(product(5L, "Widget")));

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest("Test Order", 1L, productIds)))
                .isInstanceOf(ReferencedResourceNotFoundException.class)
                .hasMessage("Product ids [7, 9] do not exist");

        verify(orderRepository, never()).save(any());
    }

    private static ProductRepository.OrderProduct orderProduct(Long orderId, Long id, String description) {
        return new ProductRepository.OrderProduct() {
            @Override
            public Long getOrderId() {
                return orderId;
            }

            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getDescription() {
                return description;
            }
        };
    }

    private static Order order(Long id, String description, Customer customer) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setCustomer(customer);
        return order;
    }

    private static Product product(Long id, String description) {
        Product product = new Product();
        product.setId(id);
        product.setDescription(description);
        return product;
    }

    private static Customer customer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        return customer;
    }
}
