package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.exception.ReferencedResourceNotFoundException;
import com.example.store.mapper.OrderMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final OrderMapper orderMapper;

    // Mapping happens inside the transaction: open-in-view is disabled and the customer is lazy.
    public List<OrderDTO> findAll() {
        return orderMapper.ordersToOrderDTOs(orderRepository.findAll());
    }

    @Transactional
    public OrderDTO create(CreateOrderRequest request) {
        // findById rather than getReferenceById: the response needs the customer's name anyway, and an
        // unknown id must surface as a 422 rather than a foreign key violation at flush time.
        Customer customer = customerRepository
                .findById(request.customerId())
                .orElseThrow(() -> new ReferencedResourceNotFoundException("Customer", request.customerId()));

        // Deliberately not added to customer.getOrders(): that would load the customer's whole order
        // collection, which is not used for the rest of this transaction.
        Order order = new Order();
        order.setDescription(request.description());
        order.setCustomer(customer);
        return orderMapper.orderToOrderDTO(orderRepository.save(order));
    }
}
