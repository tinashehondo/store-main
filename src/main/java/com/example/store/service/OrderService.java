package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.OrderProductDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.entity.Product;
import com.example.store.exception.ReferencedResourceNotFoundException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.OrderMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;
import com.example.store.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final OrderMapper orderMapper;

    /**
     * At most three statements whatever the page size: the page with customers join-fetched, the count, and the
     * products of all orders on the page in one {@code order_id IN (...)} query. Join-fetching the products collection
     * instead would make Hibernate paginate in memory (see CustomerService#findAll). Mapping happens inside the
     * transaction as open-in-view is disabled.
     */
    public Page<OrderDTO> findAll(Pageable pageable) {
        Page<Order> orders = orderRepository.findAll(pageable);
        Map<Long, List<OrderProductDTO>> products =
                productsByOrderId(orders.map(Order::getId).getContent());
        return orders.map(order -> orderMapper.orderToOrderDTO(order, products.getOrDefault(order.getId(), List.of())));
    }

    // Two statements: the order with its customer join-fetched, then its products.
    public OrderDTO findById(Long id) {
        Order order =
                orderRepository.findWithCustomerById(id).orElseThrow(() -> new ResourceNotFoundException("Order", id));
        return orderMapper.orderToOrderDTO(order, productsByOrderId(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional
    public OrderDTO create(CreateOrderRequest request) {
        // findById rather than getReferenceById: the response needs the customer's name anyway, and an
        // unknown id must surface as a 422 rather than a foreign key violation at flush time.
        Customer customer = customerRepository
                .findById(request.customerId())
                .orElseThrow(() -> new ReferencedResourceNotFoundException("Customer", request.customerId()));
        List<Product> products = findProducts(request.productIds());

        // Deliberately not added to customer.getOrders(): that would load the customer's whole order
        // collection, which is not used for the rest of this transaction.
        Order order = new Order();
        order.setDescription(request.description());
        order.setCustomer(customer);
        order.setProducts(new HashSet<>(products));
        Order saved = orderRepository.save(order);

        // Same order as the read paths: products sorted by id.
        List<OrderProductDTO> productDTOs = products.stream()
                .sorted(Comparator.comparing(Product::getId))
                .map(orderMapper::productToOrderProductDTO)
                .toList();
        return orderMapper.orderToOrderDTO(saved, productDTOs);
    }

    /** All products in one query; any id that does not exist is a 422 listing every missing id. */
    private List<Product> findProducts(Set<Long> productIds) {
        List<Product> products = productRepository.findAllById(productIds);
        if (products.size() != productIds.size()) {
            Set<Long> found = products.stream().map(Product::getId).collect(Collectors.toSet());
            List<Long> missing = productIds.stream()
                    .filter(id -> !found.contains(id))
                    .sorted()
                    .toList();
            throw new ReferencedResourceNotFoundException("Product", missing);
        }
        return products;
    }

    // groupingBy keeps encounter order, so each order's products stay sorted by id.
    private Map<Long, List<OrderProductDTO>> productsByOrderId(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return productRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(
                        ProductRepository.OrderProduct::getOrderId,
                        Collectors.mapping(orderMapper::orderProductToOrderProductDTO, Collectors.toList())));
    }
}
