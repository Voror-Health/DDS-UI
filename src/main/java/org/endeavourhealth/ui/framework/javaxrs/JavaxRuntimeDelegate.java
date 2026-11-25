package org.endeavourhealth.ui.framework.javaxrs;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.Variant;
import javax.ws.rs.ext.RuntimeDelegate;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A minimal implementation of the old {@code javax.ws.rs} API, so that Endeavour library code that creates javax
 * JAX-RS exceptions or responses (e.g. {@code QueueHelper.findExchangeConfig} throws
 * {@code javax.ws.rs.BadRequestException}) works in this Jakarta webapp.
 *
 * <p>Without it, javax's {@code RuntimeDelegate} falls back to the class name
 * {@code org.glassfish.jersey.internal.RuntimeDelegateImpl}, finds Jersey 3's (which implements the jakarta
 * interface) and fails with a {@code LinkageError}, so the library's real error message is lost and Jersey logs
 * "An exception mapping did not successfully produce and processed a response". Registered through
 * {@code META-INF/services/javax.ws.rs.ext.RuntimeDelegate}.</p>
 *
 * <p>Only response building is supported, which is all exception constructors need. Nothing here is a javax
 * client or server: library code using the javax JAX-RS <em>client</em> ({@code PostToRest}, UPRN) still can't run.</p>
 */
public class JavaxRuntimeDelegate extends RuntimeDelegate {

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new Builder();
    }

    @Override
    public UriBuilder createUriBuilder() {
        throw unsupported();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw unsupported();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw unsupported();
    }

    @Override
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        //String.valueOf is all the simple response below needs (getStringHeaders)
        return new HeaderDelegate<>() {
            @Override
            public T fromString(String value) {
                throw unsupported();
            }

            @Override
            public String toString(T value) {
                //MediaType, EntityTag etc. implement toString() by calling this delegate, so never call theirs
                if (value instanceof MediaType m) {
                    StringBuilder sb = new StringBuilder(m.getType()).append('/').append(m.getSubtype());
                    m.getParameters().forEach((k, v) -> sb.append(';').append(k).append('=').append(v));
                    return sb.toString();
                }
                if (value instanceof EntityTag e) {
                    return (e.isWeak() ? "W/" : "") + '"' + e.getValue() + '"';
                }
                if (value != null && value.getClass().getName().startsWith("javax.ws.rs.core.")) {
                    return value.getClass().getSimpleName();
                }
                return String.valueOf(value);
            }
        };
    }

    @Override
    public Link.Builder createLinkBuilder() {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Not supported by the minimal javax.ws.rs delegate in vhp-dds-ui");
    }

    static final class Builder extends Response.ResponseBuilder {
        private Response.StatusType status = Response.Status.OK;
        private Object entity;
        private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        @Override
        public Response build() {
            return new SimpleResponse(status, entity, new MultivaluedHashMap<>(headers));
        }

        @Override
        @SuppressWarnings("MethodDoesntCallSuperMethod")
        public Response.ResponseBuilder clone() {
            Builder b = new Builder();
            b.status = status;
            b.entity = entity;
            b.headers.putAll(headers);
            return b;
        }

        @Override
        public Response.ResponseBuilder status(int code) {
            return status(code, null);
        }

        @Override
        public Response.ResponseBuilder status(int code, String reasonPhrase) {
            Response.Status known = Response.Status.fromStatusCode(code);
            String reason = reasonPhrase != null ? reasonPhrase : known != null ? known.getReasonPhrase() : "";
            this.status = new Response.StatusType() {
                public int getStatusCode() {
                    return code;
                }

                public Response.Status.Family getFamily() {
                    return Response.Status.Family.familyOf(code);
                }

                public String getReasonPhrase() {
                    return reason;
                }
            };
            return this;
        }

        @Override
        public Response.ResponseBuilder entity(Object entity) {
            this.entity = entity;
            return this;
        }

        @Override
        public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) {
            return entity(entity);
        }

        @Override
        public Response.ResponseBuilder header(String name, Object value) {
            if (value == null) {
                headers.remove(name);
            } else {
                headers.add(name, value);
            }
            return this;
        }

        @Override
        public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> newHeaders) {
            headers.clear();
            if (newHeaders != null) {
                headers.putAll(newHeaders);
            }
            return this;
        }

        @Override
        public Response.ResponseBuilder type(MediaType type) {
            return header("Content-Type", type);
        }

        @Override
        public Response.ResponseBuilder type(String type) {
            return header("Content-Type", type);
        }

        @Override
        public Response.ResponseBuilder allow(String... methods) {
            return header("Allow", String.join(",", methods));
        }

        @Override
        public Response.ResponseBuilder allow(Set<String> methods) {
            return header("Allow", String.join(",", methods));
        }

        @Override
        public Response.ResponseBuilder cacheControl(CacheControl cacheControl) {
            return header("Cache-Control", cacheControl);
        }

        @Override
        public Response.ResponseBuilder encoding(String encoding) {
            return header("Content-Encoding", encoding);
        }

        @Override
        public Response.ResponseBuilder language(String language) {
            return header("Content-Language", language);
        }

        @Override
        public Response.ResponseBuilder language(Locale language) {
            return header("Content-Language", language);
        }

        @Override
        public Response.ResponseBuilder variant(Variant variant) {
            return this;
        }

        @Override
        public Response.ResponseBuilder contentLocation(URI location) {
            return header("Content-Location", location);
        }

        @Override
        public Response.ResponseBuilder cookie(NewCookie... cookies) {
            return this;
        }

        @Override
        public Response.ResponseBuilder expires(Date expires) {
            return header("Expires", expires);
        }

        @Override
        public Response.ResponseBuilder lastModified(Date lastModified) {
            return header("Last-Modified", lastModified);
        }

        @Override
        public Response.ResponseBuilder location(URI location) {
            return header("Location", location);
        }

        @Override
        public Response.ResponseBuilder tag(EntityTag tag) {
            return header("ETag", tag);
        }

        @Override
        public Response.ResponseBuilder tag(String tag) {
            return header("ETag", tag);
        }

        @Override
        public Response.ResponseBuilder variants(Variant... variants) {
            return this;
        }

        @Override
        public Response.ResponseBuilder variants(List<Variant> variants) {
            return this;
        }

        @Override
        public Response.ResponseBuilder links(Link... links) {
            return this;
        }

        @Override
        public Response.ResponseBuilder link(URI uri, String rel) {
            return this;
        }

        @Override
        public Response.ResponseBuilder link(String uri, String rel) {
            return this;
        }
    }

    static final class SimpleResponse extends Response {
        private final StatusType status;
        private final Object entity;
        private final MultivaluedMap<String, Object> headers;

        SimpleResponse(StatusType status, Object entity, MultivaluedMap<String, Object> headers) {
            this.status = status;
            this.entity = entity;
            this.headers = headers;
        }

        @Override
        public int getStatus() {
            return status.getStatusCode();
        }

        @Override
        public StatusType getStatusInfo() {
            return status;
        }

        @Override
        public Object getEntity() {
            return entity;
        }

        @Override
        public <T> T readEntity(Class<T> entityType) {
            return entityType.cast(entity);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T readEntity(GenericType<T> entityType) {
            return (T) entity;
        }

        @Override
        public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
            return readEntity(entityType);
        }

        @Override
        public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
            return readEntity(entityType);
        }

        @Override
        public boolean hasEntity() {
            return entity != null;
        }

        @Override
        public boolean bufferEntity() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public MediaType getMediaType() {
            Object t = headers.getFirst("Content-Type");
            if (t == null || t instanceof MediaType) {
                return (MediaType) t;
            }
            //simple "type/subtype" parse (MediaType.valueOf would need a full header delegate)
            String s = t.toString();
            int semi = s.indexOf(';');
            String[] parts = (semi < 0 ? s : s.substring(0, semi)).trim().split("/", 2);
            return parts.length == 2 ? new MediaType(parts[0], parts[1]) : new MediaType(parts[0], MediaType.MEDIA_TYPE_WILDCARD);
        }

        @Override
        public Locale getLanguage() {
            return null;
        }

        @Override
        public int getLength() {
            return -1;
        }

        @Override
        public Set<String> getAllowedMethods() {
            return Set.of();
        }

        @Override
        public Map<String, NewCookie> getCookies() {
            return Map.of();
        }

        @Override
        public EntityTag getEntityTag() {
            return null;
        }

        @Override
        public Date getDate() {
            return null;
        }

        @Override
        public Date getLastModified() {
            return null;
        }

        @Override
        public URI getLocation() {
            Object l = headers.getFirst("Location");
            return l instanceof URI u ? u : null;
        }

        @Override
        public Set<Link> getLinks() {
            return Set.of();
        }

        @Override
        public boolean hasLink(String relation) {
            return false;
        }

        @Override
        public Link getLink(String relation) {
            return null;
        }

        @Override
        public Link.Builder getLinkBuilder(String relation) {
            return null;
        }

        @Override
        public MultivaluedMap<String, Object> getMetadata() {
            return headers;
        }

        @Override
        public MultivaluedMap<String, String> getStringHeaders() {
            MultivaluedMap<String, String> ret = new MultivaluedHashMap<>();
            headers.forEach((k, values) -> values.forEach(v -> ret.add(k, String.valueOf(v))));
            return ret;
        }

        @Override
        public String getHeaderString(String name) {
            List<Object> values = headers.get(name);
            if (values == null) {
                return null;
            }
            return String.join(",", values.stream().map(String::valueOf).toList());
        }
    }
}
