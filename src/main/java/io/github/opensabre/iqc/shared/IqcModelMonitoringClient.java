package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.entity.vo.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.Instant;
import java.util.Map;

/** Calls the existing control plane through the framework's signed internal Feign hop. */
@FeignClient(name="base-gateway-admin",contextId="iqcModelMonitoring")
public interface IqcModelMonitoringClient {
    @GetMapping("/monitoring/iqc/models/history")
    Result<History> history(@RequestParam("range") String range);
    @GetMapping("/monitoring/iqc/models/status")
    Result<SourceStatus> status();
    record SourceStatus(boolean available,String message,String targets) { }
    record History(String range,Instant start,Instant end,long stepSeconds,Map<String,String> series) { }
}
