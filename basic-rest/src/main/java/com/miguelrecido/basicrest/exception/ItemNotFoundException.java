package com.miguelrecido.basicrest.exception;

public class ItemNotFoundException extends RuntimeException {
    public ItemNotFoundException(Long id) {
        super("Item %d not found".formatted(id));
    }
}
