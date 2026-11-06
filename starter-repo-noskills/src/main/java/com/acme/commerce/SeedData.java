package com.acme.commerce;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SeedData implements CommandLineRunner {
    private final OrderRepository orders;
    public SeedData(OrderRepository orders) { this.orders = orders; }
    @Override public void run(String... args) {
        orders.save(new Order("O-100", "M-01", 10000, "ALIPAY", "PAY-A-100", OrderStatus.PAID));
        orders.save(new Order("O-200", "M-01", 20000, "WECHAT", "PAY-W-200", OrderStatus.PAID));
        orders.save(new Order("O-FAIL-300", "M-01", 10000, "CARD", "PAY-C-300", OrderStatus.PAID));
        orders.save(new Order("O-400", "M-02", 5000, "ALIPAY", "PAY-A-400", OrderStatus.PAID));
    }
}
