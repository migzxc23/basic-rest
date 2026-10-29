package com.miguelrecido.basicrest.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Instant createdAt,
        Instant updatedAt) {

    public static ItemResponse from(Item i) {
        return new ItemResponse(i.getId(), i.getName(), i.getDescription(), i.getPrice(),
                i.getCreatedAt(), i.getUpdatedAt());
    }
}
