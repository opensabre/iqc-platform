package io.github.opensabre.iqc.risk;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
import java.util.Set;

@Service @RequiredArgsConstructor
public class RiskCenterQueryService {
    public record Page(List<RiskEventQueryService.View> events,String nextAfterId) { }
    private final RiskEventMapper events;
    private final RiskEventQueryService queries;
    private final IqcDataScope scope;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String after,String status,String risk) {
        if(after!=null && !after.matches("risk-[a-f0-9]{59}")
                || status!=null && !Set.of("OPEN","IN_PROGRESS","RESOLVED","DISMISSED").contains(status)
                || risk!=null && !Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(risk))
            throw IqcException.invalidArgument("风险中心游标或筛选无效");
        boolean all=scope.canViewAll();String owner=scope.owner(),group=all?null:scope.groupId();
        var rows=events.center(all,owner,group,after,status,risk);
        boolean more=rows.size()>20;
        var selected=rows.subList(0,Math.min(20,rows.size()));
        var byResult=new java.util.LinkedHashMap<String,java.util.List<String>>();
        for(var row:selected)byResult.computeIfAbsent(row.sourceResultId(),ignored->new java.util.ArrayList<>()).add(row.id());
        var verified=new java.util.HashMap<String,RiskEventQueryService.View>();
        for(var groupRows:byResult.entrySet()) {
            // Full source proofs are held one result at a time; only small event views survive this loop.
            for(var view:queries.list(groupRows.getKey()))if(groupRows.getValue().contains(view.id()))verified.put(view.id(),view);
        }
        var views=new java.util.ArrayList<RiskEventQueryService.View>();
        for(var row:selected) {
            var view=verified.get(row.id());
            if(view==null || status!=null && !status.equals(view.status()) || risk!=null && !risk.equals(view.riskLevel()))
                throw IqcException.invalidState("风险中心事件与实际生产来源不一致");
            views.add(view);
        }
        return new Page(List.copyOf(views),more?selected.get(selected.size()-1).id():null);
    }
}
