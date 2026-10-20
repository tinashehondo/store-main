package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.CustomerOrderDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.mapper.CustomerMapperImpl;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private OrderRepository orderRepository;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, orderRepository, new CustomerMapperImpl());
    }

    @Test
    void findAllLoadsOrdersOfThePageInOneQueryAndGroupsThemByCustomer() {
        Customer john = customer(1L, "John Doe");
        Customer jane = customer(2L, "Jane Doe");
        Customer nobody = customer(3L, "No Orders");
        Pageable pageable = PageRequest.of(0, 3);
        when(customerRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(john, jane, nobody), pageable, 10));
        when(orderRepository.findByCustomerIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(order(5L, "A", john), order(6L, "B", jane), order(7L, "C", john)));

        Page<CustomerDTO> result = customerService.findAll(pageable);

        assertThat(result.getTotalElements()).isEqualTo(10);
        assertThat(result.getContent()).extracting(CustomerDTO::getId).containsExactly(1L, 2L, 3L);
        assertThat(result.getContent().get(0).getOrders())
                .extracting(CustomerOrderDTO::getId, CustomerOrderDTO::getDescription)
                .containsExactly(tuple(5L, "A"), tuple(7L, "C"));
        assertThat(result.getContent().get(1).getOrders())
                .extracting(CustomerOrderDTO::getId)
                .containsExactly(6L);
        assertThat(result.getContent().get(2).getOrders()).isEmpty();
        verify(orderRepository).findByCustomerIdIn(List.of(1L, 2L, 3L));
    }

    @Test
    void findAllOnEmptyPageDoesNotQueryOrders() {
        Pageable pageable = PageRequest.of(5, 20);
        when(customerRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 100));

        Page<CustomerDTO> result = customerService.findAll(pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(100);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void createPersistsNewCustomerFromRequest() {
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer toSave = invocation.getArgument(0);
            // Checked before the simulated id assignment: the entity must be new.
            assertThat(toSave.getId()).isNull();
            assertThat(toSave.getName()).isEqualTo("John Doe");
            toSave.setId(101L);
            return toSave;
        });

        CustomerDTO result = customerService.create(new CreateCustomerRequest("John Doe"));

        verify(customerRepository).save(any(Customer.class));
        assertThat(result.getId()).isEqualTo(101L);
        assertThat(result.getName()).isEqualTo("John Doe");
        assertThat(result.getOrders()).isEmpty();
    }

    private static Order order(Long id, String description, Customer customer) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setCustomer(customer);
        return order;
    }

    private static Customer customer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        return customer;
    }
}
