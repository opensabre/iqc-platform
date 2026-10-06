package io.github.opensabre.iqc.schedule;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import io.github.opensabre.common.core.util.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/** Revalidate organization grants at each tick; only creator scope bounds persist, never bearer credentials. */
@Service @RequiredArgsConstructor
public class ScheduledActorAccessService {
    public record CreationBound(String schemaVersion,String userId,String owner,boolean all,String groupId) { }
    public record Resolved(String userId,String owner,boolean all,String groupId,Set<String> roles,
                           Set<String> resourceCodes,Instant checkedAt) { }
    private final IqcOrganizationClient organization;
    private final IqcDataScope scope;
    private final io.github.opensabre.security.context.InternalTokenUserContext tokens;

    public CreationBound capture() {
        String owner=scope.owner();if("system".equals(owner))throw IqcException.accessDenied("周期任务需要已认证负责人");
        var response=organization.getUserByUniqueId(owner);var user=response==null?null:response.getData();
        if(user==null || user.id()==null || !owner.equals(user.username()))throw IqcException.accessDenied("无法确定周期负责人稳定身份");
        return new CreationBound("iqc-schedule-owner-scope-v1",user.id(),owner,scope.canViewAll(),scope.groupId());
    }
    public <T> T runAsCurrentOwner(CreationBound bound,Supplier<T> callback) {
        var resolved=resolve(bound);return scope.asScheduledActor(resolved,callback);
    }
    public Resolved resolve(CreationBound bound) {
        return resolve(bound,Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:schedule:publish"));
    }
    /** Required grants are selected by the owning server workflow, never from a received payload. */
    public Resolved resolve(CreationBound bound,Set<String> requiredResources) {
        if(requiredResources==null || requiredResources.isEmpty())throw IqcException.invalidState("后台工作流缺少固定授权要求");
        requireBackground();
        if(tokens.currentClaims()!=null)throw IqcException.invalidState("周期后台不能继承用户令牌");
        if(bound==null || !"iqc-schedule-owner-scope-v1".equals(bound.schemaVersion()) || bound.userId()==null
                || !bound.userId().matches("[A-Za-z0-9:_-]{1,64}") || bound.owner()==null || bound.owner().isBlank()
                || "system".equals(bound.owner()))throw IqcException.invalidState("周期负责人范围证据无效");
        var response=organization.schedulerAccess(bound.userId());var current=response==null?null:response.getData();
        Instant now=Instant.now();
        if(current==null || !"iqc-scheduler-access-v1".equals(current.schemaVersion()) || !bound.userId().equals(current.userId())
                || !current.accountValid() || !bound.owner().equals(current.username()) || current.roles()==null || current.resourceCodes()==null
                || current.checkedAt()==null || current.checkedAt().isBefore(now.minusSeconds(30)) || current.checkedAt().isAfter(now.plusSeconds(5)))
            throw IqcException.accessDenied("周期负责人已失效或未取得当前授权证据");
        if(!current.resourceCodes().containsAll(requiredResources))
            throw IqcException.accessDenied("周期负责人已失去创建或执行权限");
        boolean currentAll=current.roles().stream().map(ScheduledActorAccessService::normalizeRole)
                .anyMatch(role->Set.of("ADMIN","SUPER_ADMIN").contains(role)) || current.resourceCodes().contains("iqc:result:all");
        String group=bound.groupId()!=null && bound.groupId().equals(current.groupId())?bound.groupId():null;
        return new Resolved(bound.userId(),bound.owner(),bound.all()&&currentAll,group,
                Set.copyOf(current.roles()),Set.copyOf(current.resourceCodes()),current.checkedAt());
    }
    public static void requireBackground() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null || !UserContextHolder.getInstance().getContext().isEmpty())
            throw IqcException.invalidState("当前授权解析仅允许无用户上下文的周期后台执行");
    }
    private static String normalizeRole(String value) {
        String role=value.startsWith("ROLE_")?value.substring(5):value;return role.toUpperCase(Locale.ROOT);
    }
}
