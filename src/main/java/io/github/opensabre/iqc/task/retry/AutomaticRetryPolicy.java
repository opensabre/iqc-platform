package io.github.opensabre.iqc.task.retry;
import io.github.opensabre.iqc.governance.IqcException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=AutomaticRetryPolicyDeserializer.class)
public record AutomaticRetryPolicy(String schemaVersion,int maximumAttempts,long initialDelaySeconds,int multiplier,
                                   long maximumDelaySeconds,Instant effectiveUntil) {
    public AutomaticRetryPolicy {
        if(!"iqc-automatic-retry-v1".equals(schemaVersion) || maximumAttempts<1 || maximumAttempts>10
                || initialDelaySeconds<1 || initialDelaySeconds>86400 || multiplier<1 || multiplier>4
                || maximumDelaySeconds<initialDelaySeconds || maximumDelaySeconds>604800)
            throw IqcException.invalidArgument("自动重试策略参数无效");
        if(effectiveUntil!=null){effectiveUntil=effectiveUntil.truncatedTo(ChronoUnit.MILLIS);
            int year=effectiveUntil.atZone(java.time.ZoneOffset.UTC).getYear();
            if(year<1000 || year>9999)throw IqcException.invalidArgument("自动重试有效期超出存储范围");}
    }
    public long delaySeconds(int attempt){long delay=initialDelaySeconds;
        for(int i=1;i<attempt && delay<maximumDelaySeconds;i++)delay=Math.min(maximumDelaySeconds,delay*multiplier);
        return delay;}
}
