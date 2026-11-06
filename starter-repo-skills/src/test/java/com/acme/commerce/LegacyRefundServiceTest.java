package com.acme.commerce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyRefundServiceTest {
    @Test
    void legacyServiceHasExpectedPublicContract() {
        assertNotNull(LegacyRefundService.class);
        assertEquals(double.class, LegacyRefundService.class.getDeclaredMethods()[0].getReturnType());
    }
}
