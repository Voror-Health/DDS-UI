package org.endeavourhealth.ui.framework;

import org.endeavourhealth.ui.framework.exceptions.BaseExceptionMapper;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;

/**
 * The unauthenticated servlet ({@code /public/*}), as in the original web.xml's "Public Resources" servlet.
 * It serves only what the SPA needs before it holds a Casdoor token (login config and token exchange).
 */
public class PublicJerseyApplication extends ResourceConfig {

    public PublicJerseyApplication() {
        packages("org.endeavourhealth.ui.endpoints_public");
        register(JacksonFeature.class);
        register(BaseExceptionMapper.class);
    }
}
