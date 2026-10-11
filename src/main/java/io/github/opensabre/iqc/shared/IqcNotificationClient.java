package io.github.opensabre.iqc.shared;

import io.github.opensabre.common.core.entity.vo.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Reuses the owning notification service; no alternate notification persistence or channel. */
@FeignClient(name="${iqc.notification.service-id:base-sysadmin}", configuration=IqcNotificationFeignConfiguration.class)
public interface IqcNotificationClient {
    @PostMapping("/internal/iqc/notifications/{requestId}")
    Result<Status> send(@PathVariable("requestId") String requestId,@RequestBody Send send);
    @GetMapping("/internal/iqc/notifications/{requestId}")
    Result<Status> lookup(@PathVariable("requestId") String requestId);
    record Send(String target,String sceneCode,String channel,Map<String,String> args) { }
    record Status(String schemaVersion,String requestId,String recordId,String requestHash,String declaredRequestHash,
                  String status,String messageId) { }
}
