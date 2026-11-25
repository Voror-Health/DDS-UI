package org.endeavourhealth.ui.framework.exceptions;

import jakarta.ws.rs.core.Response;

public final class BadRequestException extends MappedException {

    //specifically not providing a default constructor, so a message must always be supplied
    public BadRequestException(String message) {
        super(message);
    }

    @Override
    public Response.Status getResponseStatus() {
        return Response.Status.BAD_REQUEST;
    }
}
