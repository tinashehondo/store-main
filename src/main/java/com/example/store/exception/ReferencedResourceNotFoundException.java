package com.example.store.exception;

/** A request body references a resource (by id) that does not exist. Mapped to 422. */
public class ReferencedResourceNotFoundException extends RuntimeException {

    public ReferencedResourceNotFoundException(String resource, Object id) {
        super(resource + " with id " + id + " does not exist");
    }
}
