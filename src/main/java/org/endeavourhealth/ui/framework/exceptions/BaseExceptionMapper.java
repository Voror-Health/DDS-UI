package org.endeavourhealth.ui.framework.exceptions;

import org.endeavourhealth.ui.framework.exceptions.MappedException;
import org.endeavourhealth.ui.framework.AbstractEndpoint;
import org.endeavourhealth.ui.json.JsonServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;

@Provider
public final class BaseExceptionMapper implements ExceptionMapper<Exception> {
    private static final Logger LOG = LoggerFactory.getLogger(BaseExceptionMapper.class);

    public BaseExceptionMapper() {
        //LOG.debug("Exception mapper created");
    }

    @Override
    public Response toResponse(Exception exception) {
        Response.ResponseBuilder r = null;

        //if the exception is one of our own exception objects
        if (exception instanceof MappedException) {
            MappedException me = (MappedException) exception;
            r = Response.status(me.getResponseStatus());
        }
        //if the exception is a web service application
        else if (exception instanceof WebApplicationException) {
            WebApplicationException we = (WebApplicationException) exception;
            r = Response.status(we.getResponse().getStatus());
        }
        //VHP: javax JAX-RS exceptions thrown by the Endeavour libraries (e.g. QueueHelper's BadRequestException)
        else if (exception instanceof javax.ws.rs.WebApplicationException) {
            LOG.error(exception.getMessage(), exception);
            r = Response.status(((javax.ws.rs.WebApplicationException) exception).getResponse().getStatus());
        }
        //if it's some other kind of exception (e.g. SQLException) that's got this far
        else {
            //log on the server too, since these are unexpected
            LOG.error(exception.getMessage(), exception);

            r = Response.status(Response.Status.INTERNAL_SERVER_ERROR);
        }

        //if our exception has a message, then send this in the response too
        //so even if there's an error, we can give some feedback as to what it is
        String message = exception.getMessage();
        if (message != null) {
					UUID errorId = UUID.randomUUID();
					LOG.error("Server error " + errorId.toString() + " : " + message);
            JsonServerException wrapper = new JsonServerException("Server error " + errorId.toString());
            //VHP: always JSON, or endpoints that @Produce text/plain turn a 403 etc. into a 500 (no writer for the wrapper)
            r = r.entity(wrapper).type(MediaType.APPLICATION_JSON_TYPE);
        }

        //because we've thrown an exception, we won't have completed the endPoint functions properly
        //so we need to remove the MDC markers here, so our thread is no longer associated with the user
        AbstractEndpoint.clearLogbackMarkers();

        return r.build();
    }


}
