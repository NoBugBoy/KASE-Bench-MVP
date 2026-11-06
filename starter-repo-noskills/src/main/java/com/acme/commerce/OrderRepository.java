package com.acme.commerce;

import org.springframework.stereotype.Repository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class OrderRepository {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    public Optional<Order> findById(String orderId) { return Optional.ofNullable(orders.get(orderId)); }
    public void save(Order order) { orders.put(order.getOrderId(), order); }
}
