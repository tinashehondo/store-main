package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.exception.ReferencedResourceNotFoundException;
import com.example.store.mapper.OrderMapperImpl;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;

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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, customerRepository, new OrderMapperImpl());
    }

    @Test
    void findAllMapsOrdersWithTheirCustomer() {
        Order order = new Order();
        order.setId(1L);
        order.setDescription("Test Order");
        order.setCustomer(customer(1L, "John Doe"));
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(order), pageable, 1));

        Page<OrderDTO> result = orderService.findAll(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(1L);
            assertThat(dto.getDescription()).isEqualTo("Test Order");
            assertThat(dto.getCustomer().getId()).isEqualTo(1L);
            assertThat(dto.getCustomer().getName()).isEqualTo("John Doe");
        });
    }

    @Test
    void createResolvesCustomerAndPersistsOrder() {
        Customer customer = customer(1L, "John Doe");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            saved.setId(10001L);
            return saved;
        });

        OrderDTO result = orderService.create(new CreateOrderRequest("Test Order", 1L));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("Test Order");
        assertThat(captor.getValue().getCustomer()).isSameAs(customer);
        assertThat(result.getId()).isEqualTo(10001L);
        assertThat(result.getCustomer().getId()).isEqualTo(1L);
        assertThat(result.getCustomer().getName()).isEqualTo("John Doe");
    }

    @Test
    void createWithUnknownCustomerThrowsAndDoesNotPersist() {
        when(customerRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest("Test Order", 999L)))
                .isInstanceOf(ReferencedResourceNotFoundException.class)
                .hasMessage("Customer with id 999 does not exist");

        verify(orderRepository, never()).save(any());
    }

    private static Customer customer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        return customer;
    }
}
