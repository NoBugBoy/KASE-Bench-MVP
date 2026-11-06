package com.acme.commerce;

public record ErrorResponse(
    String code,
    String message,
    String requestId
) {}
