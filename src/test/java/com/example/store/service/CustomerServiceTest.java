package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.mapper.CustomerMapperImpl;
import com.example.store.repository.CustomerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, new CustomerMapperImpl());
    }

    @Test
    void findAllMapsCustomersAndTheirOrders() {
        Customer customer = customer(1L, "John Doe");
        Order order = new Order();
        order.setId(7L);
        order.setDescription("Test Order");
        order.setCustomer(customer);
        customer.getOrders().add(order);
        when(customerRepository.findAll()).thenReturn(List.of(customer));

        List<CustomerDTO> result = customerService.findAll();

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(1L);
            assertThat(dto.getName()).isEqualTo("John Doe");
            assertThat(dto.getOrders()).singleElement().satisfies(o -> {
                assertThat(o.getId()).isEqualTo(7L);
                assertThat(o.getDescription()).isEqualTo("Test Order");
            });
        });
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

    private static Customer customer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        return customer;
    }
}
