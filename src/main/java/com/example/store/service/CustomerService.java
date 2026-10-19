package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.mapper.CustomerMapper;
import com.example.store.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    // Mapping happens inside the transaction: open-in-view is disabled and orders are lazy.
    public List<CustomerDTO> findAll() {
        return customerMapper.customersToCustomerDTOs(customerRepository.findAll());
    }

    @Transactional
    public CustomerDTO create(CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setName(request.name());
        return customerMapper.customerToCustomerDTO(customerRepository.save(customer));
    }
}
