package io.github.opensabre.iqc.risk;

import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
import java.util.Set;

@Service @RequiredArgsConstructor
public class RiskHandlingHistoryService {
    public record Page(List<RiskHandlingEventMapper.Event> events,String nextBeforeVersion) { }
    private final RiskEventQueryService queries;
    private final RiskHandlingEventMapper history;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String resultId,String id,String cursor) {
        if(id==null || !id.matches("risk-[a-f0-9]{59}"))throw IqcException.invalidArgument("风险事件标识无效");
        Long before=null;
        if(cursor!=null)try {
            if(!cursor.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();
            before=Long.parseLong(cursor);
        }catch(NumberFormatException invalid){throw IqcException.invalidArgument("风险处置历史游标无效");}
        var current=queries.list(resultId).stream().filter(row->id.equals(row.id())).findFirst()
                .orElseThrow(()->IqcException.notFound("风险事件不存在"));
        var rows=history.page(id,before);
        long ceiling=Long.parseLong(current.version());
        if(before!=null && before>ceiling && before-ceiling>1)throw IqcException.invalidArgument("风险处置历史游标超出实际版本");
        String laterStatus=null;Long laterVersion=null;
        for(var event:rows) {
            long version;
            try {version=Long.parseLong(event.version());}catch(RuntimeException invalid){throw IqcException.invalidState("风险处置历史版本损坏");}
            if(version<1 || version>ceiling || before!=null && version>=before
                    || !Set.of("OPEN","IN_PROGRESS","RESOLVED","DISMISSED").contains(event.status()==null?"":event.status())
                    || !Set.of("OPEN","IN_PROGRESS","RESOLVED","DISMISSED").contains(event.previousStatus()==null?"":event.previousStatus())
                    || event.status().equals(event.previousStatus()) || event.reason()==null || event.reason().isBlank() || event.reason().length()>1000
                    || event.actor()==null || event.actor().isBlank() || event.recordedAtUtc()==null
                    || laterVersion!=null && (version!=laterVersion-1 || !event.status().equals(laterStatus)))
                throw IqcException.invalidState("风险处置历史状态或版本链损坏");
            if(laterVersion==null && before==null && (version!=ceiling || !event.status().equals(current.status())))
                throw IqcException.invalidState("风险处置历史与当前状态不一致");
            if(laterVersion==null && before!=null && version!=Math.min(ceiling,before-1))
                throw IqcException.invalidState("风险处置历史分页边界缺失");
            laterVersion=version;laterStatus=event.previousStatus();
        }
        if(before==null && rows.isEmpty() && ceiling>0)throw IqcException.invalidState("风险处置历史缺失");
        if(rows.isEmpty() && before!=null && before>1 && ceiling>0)
            throw IqcException.invalidState("风险处置历史分页记录缺失");
        boolean more=rows.size()>50;
        if(!more && laterVersion!=null && (laterVersion!=1 || !"OPEN".equals(laterStatus)))
            throw IqcException.invalidState("风险处置历史起始记录缺失");
        var page=List.copyOf(rows.subList(0,Math.min(50,rows.size())));
        return new Page(page,more?page.get(page.size()-1).version():null);
    }
}
