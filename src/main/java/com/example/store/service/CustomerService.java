package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.mapper.CustomerMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final CustomerMapper customerMapper;

    /**
     * At most three statements whatever the page size: the page of customers, the count, and all their orders in one
     * {@code customer_id IN (...)} query.
     *
     * <p>Join-fetching the orders collection together with a {@link Pageable} would make Hibernate fetch every row and
     * paginate in memory (HHH90003004), and a separate join-fetch query would repeat the customer columns on every
     * order row. Mapping happens inside the transaction as open-in-view is disabled.
     */
    public Page<CustomerDTO> findAll(Pageable pageable) {
        return withOrders(customerRepository.findAll(pageable));
    }

    /**
     * Customers whose name contains {@code name} anywhere, case-insensitively, with the same three-statement fetch plan
     * as {@link #findAll}. The term is trimmed and its LIKE wildcards are matched literally.
     */
    public Page<CustomerDTO> search(String name, Pageable pageable) {
        return withOrders(customerRepository.findByNameLike(containsPattern(name.strip()), pageable));
    }

    /**
     * A LIKE pattern matching {@code term} as a literal substring: the escape character {@code \} itself is escaped
     * first, then the wildcards {@code %} and {@code _}.
     */
    static String containsPattern(String term) {
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private Page<CustomerDTO> withOrders(Page<Customer> customers) {
        if (customers.isEmpty()) {
            return customers.map(customerMapper::customerToCustomerDTO);
        }

        List<Long> customerIds = customers.map(Customer::getId).getContent();
        // groupingBy keeps encounter order, so each customer's orders stay sorted by id.
        Map<Long, List<Order>> ordersByCustomerId = orderRepository.findByCustomerIdIn(customerIds).stream()
                .collect(Collectors.groupingBy(order -> order.getCustomer().getId()));
        return customers.map(customer -> customerMapper.customerToCustomerDTO(
                customer, ordersByCustomerId.getOrDefault(customer.getId(), List.of())));
    }

    @Transactional
    public CustomerDTO create(CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setName(request.name());
        return customerMapper.customerToCustomerDTO(customerRepository.save(customer));
    }
}
