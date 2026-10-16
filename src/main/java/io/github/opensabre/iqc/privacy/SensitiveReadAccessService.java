package io.github.opensabre.iqc.privacy;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Current grants over the original actor projection; no role shortcuts, bearer storage or parallel ACL model. */
@Service
@RequiredArgsConstructor
public class SensitiveReadAccessService {
    public static final String FULL_CONVERSATION="iqc:sensitive:conversation:read";
    public static final String PII="iqc:sensitive:pii:read";
    public static final String PROMPT="iqc:sensitive:prompt:read";
    private final ScheduledActorAccessService actors;
    public record Access(String schemaVersion,boolean fullConversation,boolean pii,boolean prompt,Instant checkedAt){
        public void requireFullConversation(){if(!fullConversation)throw IqcException.accessDenied("缺少完整会话敏感读取权限");}
        public void requirePii(){if(!pii)throw IqcException.accessDenied("缺少客户PII读取权限");}
        public void requirePrompt(){if(!prompt)throw IqcException.accessDenied("缺少Prompt敏感读取权限");}
    }
    /** The owning read workflow supplies fixed base grants, never resource names or an Access value received from a client. */
    public Access current(Set<String> baseResources){
        if(baseResources==null||baseResources.isEmpty()||baseResources.stream().anyMatch(code->code==null||code.isBlank()))
            throw IqcException.invalidState("敏感读取缺少固定基础权限");
        var bound=actors.capture();
        var current=actors.resolveCurrent(bound,Set.copyOf(baseResources));
        // resolveCurrent already validates the principal, account, grant freshness and base resources.
        // Do not read with the old JWT's wider data scope after a group/all-scope revocation.
        if(current==null||current.resourceCodes()==null||current.checkedAt()==null||current.all()!=bound.all()
                ||!Objects.equals(current.groupId(),bound.groupId()))
            throw IqcException.accessDenied("敏感读取数据范围已变化，请重新认证");
        var grants=current.resourceCodes();
        return new Access("iqc-sensitive-read-access-v1",grants.contains(FULL_CONVERSATION),grants.contains(PII),
                grants.contains(PROMPT),current.checkedAt());
    }
}
