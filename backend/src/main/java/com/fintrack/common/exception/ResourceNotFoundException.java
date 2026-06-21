package com.fintrack.common.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String resource, Long id) {
        return new ResourceNotFoundException(resource + " not found with id: " + id);
    }

    public static ResourceNotFoundException of(String resource, String id) {
        return new ResourceNotFoundException(resource + " not found with id: " + id);
    }
}
