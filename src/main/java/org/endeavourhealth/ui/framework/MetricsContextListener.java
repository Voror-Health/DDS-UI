package org.endeavourhealth.ui.framework;

import com.codahale.metrics.MetricRegistry;
import io.dropwizard.metrics.servlet6.InstrumentedFilterContextListener;
import org.endeavourhealth.common.utility.MetricsHelper;

/**
 * Jakarta port of {@code org.endeavourhealth.coreui.framework.MetricsContextListener}.
 *
 * servlet listener to capture general metrics on Tomcat endpoint calls, such as total count.
 *
 * Note that the details metrics on individual endpoints, captured by the @Timed annotation, have nothing
 * to do with this class (see InstrumentedFilterContextListener)
 */
public class MetricsContextListener extends InstrumentedFilterContextListener {

    @Override
    protected MetricRegistry getMetricRegistry() {
        return MetricsHelper.getRegistry();
    }
}
