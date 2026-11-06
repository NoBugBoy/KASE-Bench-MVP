package com.acme.commerce;

import java.util.NoSuchElementException;

public class OrderNotFoundException extends NoSuchElementException {
    public OrderNotFoundException(String message) {
        super(message);
    }
}
