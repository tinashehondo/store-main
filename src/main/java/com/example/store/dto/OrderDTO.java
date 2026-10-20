package com.example.store.dto;

import lombok.Data;

import java.util.List;

@Data
public class OrderDTO {
    private Long id;
    private String description;
    private OrderCustomerDTO customer;
    private List<OrderProductDTO> products;
}
