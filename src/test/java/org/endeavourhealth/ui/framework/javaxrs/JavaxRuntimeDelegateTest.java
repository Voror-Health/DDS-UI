package org.endeavourhealth.ui.framework.javaxrs;

import org.junit.jupiter.api.Test;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.RuntimeDelegate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Endeavour library code (e.g. QueueHelper.findExchangeConfig) throws javax.ws.rs exceptions. In the WAR that used
 * to fail with "LinkageError: ClassCastException ... RuntimeDelegate" because javax fell back to Jersey 3's
 * (jakarta) RuntimeDelegateImpl. The services file must make javax use this delegate instead.
 */
class JavaxRuntimeDelegateTest {

    @Test
    void javaxUsesThisDelegate() {
        assertInstanceOf(JavaxRuntimeDelegate.class, RuntimeDelegate.getInstance());
    }

    @Test
    void libraryStyleExceptionsCanBeCreated() {
        BadRequestException ex = new BadRequestException("Failed to find PostMessageToExchange config details for exchange EdsSubscriber");
        assertEquals(400, ex.getResponse().getStatus());
        assertEquals("Failed to find PostMessageToExchange config details for exchange EdsSubscriber", ex.getMessage());
        assertEquals(Response.Status.Family.CLIENT_ERROR, ex.getResponse().getStatusInfo().getFamily());

        assertEquals(404, new NotFoundException().getResponse().getStatus());
    }

    @Test
    void responsesCarryStatusEntityAndHeaders() {
        Response r = Response.status(503).entity("down").header("Retry-After", 30).type("text/plain").build();
        assertEquals(503, r.getStatus());
        assertEquals("down", r.getEntity());
        assertEquals("30", r.getHeaderString("Retry-After"));
        assertEquals("text/plain", r.getMediaType().toString());
        assertEquals("Service Unavailable", r.getStatusInfo().getReasonPhrase());
    }
}
