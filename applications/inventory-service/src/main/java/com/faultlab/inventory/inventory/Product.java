package com.faultlab.inventory.inventory;

public record Product(String productId, int available, int reserved) { }
