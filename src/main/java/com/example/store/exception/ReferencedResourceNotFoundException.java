package com.example.store.exception;

import java.util.Collection;

/** A request body references a resource (by id) that does not exist. Mapped to 422. */
public class ReferencedResourceNotFoundException extends RuntimeException {

    public ReferencedResourceNotFoundException(String resource, Object id) {
        super(resource + " with id " + id + " does not exist");
    }

    /** Several referenced ids are missing; all of them are listed, so the client can fix the request in one go. */
    public ReferencedResourceNotFoundException(String resource, Collection<?> ids) {
        super(resource + " ids " + ids + " do not exist");
    }
}
