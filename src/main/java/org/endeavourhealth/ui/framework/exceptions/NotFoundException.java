package org.endeavourhealth.ui.framework.exceptions;

import org.endeavourhealth.ui.framework.exceptions.MappedException;

import jakarta.ws.rs.core.Response;

public class NotFoundException extends MappedException {
    public NotFoundException() {
        super();
    }

    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public Response.Status getResponseStatus() {
        return Response.Status.NOT_FOUND;
    }
}