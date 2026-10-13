package io.github.opensabre.iqc.action;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable deployment-owned routes; published policies select exact versions rather than arbitrary URLs. */
public final class ExternalActionDestinations {
    private record Key(String id,int version) { }
    /** No credential value is stored here; authentication references an explicitly configured environment secret. */
    public record Destination(String id,int version,ActionExecutionContract.Type type,URI sendUri,URI receiptsUri,
            int connectTimeoutMs,int requestTimeoutMs,int maximumResponseBytes,String bearerTokenEnvironmentVariable) {
        public Destination {
            if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || version<1)
                throw new IllegalArgumentException("Destination requires exact id and positive version");
            ExternalActionPreparationService.definition(type);
            requireHttps(sendUri);requireHttps(receiptsUri);
            if(!receiptsUri.getPath().endsWith("/"))throw new IllegalArgumentException("Receipt endpoint requires a trailing slash");
            if(connectTimeoutMs<1 || requestTimeoutMs<connectTimeoutMs || requestTimeoutMs>60000
                    || maximumResponseBytes<1 || maximumResponseBytes>65536)
                throw new IllegalArgumentException("Destination transport limits are invalid");
            if(bearerTokenEnvironmentVariable!=null && !bearerTokenEnvironmentVariable.matches("[A-Z][A-Z0-9_]{0,127}"))
                throw new IllegalArgumentException("Destination secret reference is invalid");
        }
        /** Only a verified idempotency key is appended; no caller-controlled URL or path is accepted. */
        public URI receiptUri(String key) {
            if(key==null || !key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Receipt key is invalid");
            return receiptsUri.resolve(key);
        }
        private static void requireHttps(URI uri) {
            if(uri==null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || uri.getPort()==0 || uri.getPort()>65535)
                throw new IllegalArgumentException("Destination requires an absolute HTTPS endpoint without credentials/query/fragment");
        }
    }
    private final Map<Key,Destination> routes;
    public ExternalActionDestinations(List<Destination> destinations) {
        var values=new HashMap<Key,Destination>();
        for(var destination:List.copyOf(destinations)){
            Objects.requireNonNull(destination);
            if(values.putIfAbsent(new Key(destination.id(),destination.version()),destination)!=null)
                throw new IllegalArgumentException("Duplicate destination id/version");
        }
        routes=Map.copyOf(values);
    }
    /** Missing versions and type mismatches stay unavailable; there is no latest-version fallback. */
    public Destination require(ExternalActionConfiguration config,ActionExecutionContract.Type type) {
        Objects.requireNonNull(config);
        var destination=routes.get(new Key(config.destinationId(),config.destinationVersion()));
        if(destination==null || destination.type()!=type)throw new IllegalArgumentException("Exact external action destination is unavailable");
        return destination;
    }
}
