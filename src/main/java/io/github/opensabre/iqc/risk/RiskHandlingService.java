package io.github.opensabre.iqc.risk;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Optimistic business handling with an atomic immutable reason; never changes machine findings. */
@Service @RequiredArgsConstructor
public class RiskHandlingService {
    private final RiskEventQueryService queries;
    private final RiskEventMapper events;
    private final RiskHandlingEventMapper history;
    private final IqcDataScope scope;
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public boolean handle(String resultId,String id,String observedVersion,String next,String reason) {
        if(id==null || !id.matches("risk-[a-f0-9]{59}") || observedVersion==null
                || !observedVersion.matches("0|[1-9][0-9]{0,18}") || next==null
                || !Set.of("OPEN","IN_PROGRESS","RESOLVED","DISMISSED").contains(next)
                || reason==null || reason.isBlank() || reason.length()>1000
                || reason.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw IqcException.invalidArgument("风险处置观察版本、状态或原因无效");
        long version;
        try {version=Long.parseLong(observedVersion);}
        catch(NumberFormatException invalid){throw IqcException.invalidArgument("风险处置版本超出范围");}
        if(version==Long.MAX_VALUE)throw IqcException.invalidState("风险处置版本已耗尽");
        String actor=scope.owner();
        if(actor==null || "system".equals(actor))throw IqcException.accessDenied("风险处置需要实际用户");
        // Scope/network resolution and full historical production proof precede all mutable event locks.
        var observed=queries.list(resultId).stream().filter(row->id.equals(row.id())).findFirst()
                .orElseThrow(()->IqcException.notFound("风险事件不存在"));
        if(!observed.version().equals(observedVersion))return false;
        String previous=observed.status();
        boolean valid=switch(previous) {
            case "OPEN" -> Set.of("IN_PROGRESS","RESOLVED","DISMISSED").contains(next);
            case "IN_PROGRESS" -> Set.of("RESOLVED","DISMISSED").contains(next);
            case "RESOLVED","DISMISSED" -> "OPEN".equals(next);
            default -> false;
        };
        if(!valid)throw IqcException.invalidState("风险处置状态转换无效");
        var current=events.selectOne(Wrappers.<RiskEvent>lambdaQuery().eq(RiskEvent::getId,id).last("FOR UPDATE"));
        if(current==null || !java.util.Objects.equals(current.getSourceResultId(),resultId)
                || !java.util.Objects.equals(current.getVersion(),version) || !previous.equals(current.getStatus()))return false;
        var now=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        if(events.handle(id,resultId,version,previous,next,actor,now)!=1)return false;
        if(history.append(id,version+1,previous,next,reason.trim(),actor,now)!=1)
            throw IqcException.invalidState("风险处置原因未能与状态一起保存");
        return true;
    }
}
