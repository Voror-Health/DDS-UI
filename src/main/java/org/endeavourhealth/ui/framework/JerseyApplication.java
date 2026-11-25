package org.endeavourhealth.ui.framework;

import com.codahale.metrics.MetricRegistry;
import io.dropwizard.metrics.jersey31.InstrumentedResourceMethodApplicationListener;
import org.endeavourhealth.common.utility.MetricsHelper;
import org.endeavourhealth.ui.framework.exceptions.BaseExceptionMapper;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

/**
 * Jakarta port of {@code org.endeavourhealth.coreui.framework.JerseyApplication} for the protected servlet
 * ({@code /api/*}, {@code /machine-api/*}).
 *
 * <p>Registers what the original listed in web.xml: the endpoint package, Jackson, the role check
 * ({@link RolesAllowedDynamicFeature} replaces Keycloak's CustomRolesAllowedFeature; roles come from the
 * Casdoor token via {@code isUserInRole}) and {@link BaseExceptionMapper}. Swagger is not registered: it
 * needed the javax-only swagger-jersey2-jaxrs integration.</p>
 */
public class JerseyApplication extends ResourceConfig {

    public JerseyApplication() {
        packages("org.endeavourhealth.ui.endpoints");

        register(JacksonFeature.class);
        register(RolesAllowedDynamicFeature.class);
        register(BaseExceptionMapper.class);

        //simply register the MetricsRegistry (which is already configured to connect to Graphite) with the app
        MetricRegistry registry = MetricsHelper.getRegistry();
        register(new InstrumentedResourceMethodApplicationListener(registry));
    }
}
