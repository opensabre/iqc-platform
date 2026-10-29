package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.*;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.security.context.InternalTokenUserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Current IQC authorization composition; remote organization evidence is controlled, not a live PDP. */
class BusinessFindingCurrentAccessTest {
    final IqcOrganizationClient organization=mock(IqcOrganizationClient.class);
    final IqcDataScope scope=new IqcDataScope(organization);
    final SensitiveReadAccessService sensitive=new SensitiveReadAccessService(new ScheduledActorAccessService(organization,scope,mock(InternalTokenUserContext.class)));
    final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    final ConversationInspectionResultMapper results=mock(ConversationInspectionResultMapper.class);
    final BusinessItemReviewService reviews=mock(BusinessItemReviewService.class);
    final BusinessQualityReportService service=new BusinessQualityReportService(tasks,results,reviews,scope,new ObjectMapper(),mock(TaskExecutionMapper.class),null,null,sensitive);
    final Set<String> grants=Set.of("iqc:report:view",SensitiveReadAccessService.FULL_CONVERSATION,SensitiveReadAccessService.PII);
    @BeforeEach void currentUser(){
        clear();context("IQC_ADMIN","");
        var user=result(new IqcOrganizationClient.OrganizationUser("user","display","owner","group","group"));
        when(organization.getUserByUniqueId("owner")).thenReturn(user);
        projection("user","owner",true,"group",Set.of("IQC_ADMIN"),grants,Instant.now());
    }
    @AfterEach void clear(){UserContextHolder.getInstance().clear();SecurityContextHolder.clearContext();}
    void context(String role,String scopes){var holder=UserContextHolder.getInstance();holder.setContext(Map.of(holder.KEY_USERNAME,"owner",holder.KEY_USER_ID,"user",holder.KEY_ROLES,role,holder.KEY_SCOPES,scopes));}
    @SuppressWarnings("unchecked") <T> Result<T> result(T value){Result<T> result=mock(Result.class);when(result.getData()).thenReturn(value);return result;}
    void projection(String id,String owner,boolean valid,String group,Set<String> roles,Set<String> resources,Instant checked){
        var current=result(new IqcOrganizationClient.SchedulerAccess("iqc-scheduler-access-v1",id,owner,valid,group,roles,resources,checked));
        when(organization.currentActionAccess("user")).thenReturn(current);
    }
    BusinessQualityReportService.Report report(){return service.report(List.of("task"),null,"SELECTED_RUNS",false,false,true);}
    void task(String owner,String group){
        var task=new InspectionTask();task.setId("task");task.setCreatedBy(owner);task.setOwnerGroupId(group);task.setConversationIdsJson("[\"conversation\"]");
        task.setRuleSnapshotJson("""
          {"schemeSnapshot":{"schemeId":"s","versionNo":1,"kind":"PUBLISHED","release":{"definition":{
          "schemaVersion":"iqc-scheme-v2","items":[{"itemCode":"a","name":"check","rule":{"id":"r","versionNo":1},"hitMeaning":"VIOLATION"}],
          "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":90,"items":[{"itemCode":"a","points":100,"veto":false}]}}}}}
          """);when(tasks.selectById("task")).thenReturn(task);
    }
    @Test void currentGrantCompositionAllowsSameGroupWithoutBorrowingBackgroundIdentity(){
        task("other-owner","group");var report=report();assertThat(report.conversationCount()).isEqualTo(1);assertThat(report.findingDistributions()).isEmpty();
        verify(organization).currentActionAccess("user");verify(organization,never()).schedulerAccess(anyString());
        assertThat(UserContextHolder.getInstance().getUsername()).isEqualTo("owner");verifyNoInteractions(reviews);
    }
    @ParameterizedTest @ValueSource(strings={"iqc:report:view","iqc:sensitive:conversation:read","iqc:sensitive:pii:read"})
    void eachRevokedGrantRejectsBeforeTaskReadEvenWithAdministratorRole(String missing){
        context("ADMIN","");var current=new HashSet<>(grants);current.remove(missing);
        projection("user","owner",true,"group",Set.of("ADMIN"),current,Instant.now());
        assertThatThrownBy(this::report).isInstanceOf(io.github.opensabre.iqc.governance.IqcException.class);
        verifyNoInteractions(tasks,results,reviews);
    }
    @ParameterizedTest @ValueSource(strings={"stale","future","disabled","identity","renamed","groupChanged","globalRevoked"})
    void invalidOrNarrowedFreshEvidenceCannotReachTaskData(String kind){
        if(kind.equals("globalRevoked"))context("ADMIN","");
        projection(kind.equals("identity")?"other":"user",kind.equals("renamed")?"renamed":"owner",!kind.equals("disabled"),kind.equals("groupChanged")?"new-group":"group",Set.of("IQC_ADMIN"),grants,
            kind.equals("stale")?Instant.now().minusSeconds(60):kind.equals("future")?Instant.now().plusSeconds(60):Instant.now());
        assertThatThrownBy(this::report).isInstanceOf(io.github.opensabre.iqc.governance.IqcException.class);verifyNoInteractions(tasks,results,reviews);
    }
    @Test void widerCurrentScopeCannotExpandOriginalUserContext(){
        task("other-owner","other-group");projection("user","owner",true,"group",Set.of("ADMIN"),grants,Instant.now());
        assertThatThrownBy(this::report).hasMessageContaining("无权统计");verifyNoInteractions(results,reviews);assertThat(scope.canViewAll()).isFalse();
    }
    @Test void restoredGrantNeedsFreshProjectionOnNextRead(){
        task("owner","other-group");assertThat(report().groups()).hasSize(1);clearInvocations(tasks,results,reviews);
        projection("user","owner",true,"group",Set.of("IQC_ADMIN"),Set.of("iqc:report:view"),Instant.now());
        assertThatThrownBy(this::report).hasMessageContaining("敏感读取");verifyNoInteractions(tasks,results,reviews);
        projection("user","owner",true,"group",Set.of("IQC_ADMIN"),grants,Instant.now());assertThat(report().groups()).hasSize(1);
        verify(organization,times(3)).currentActionAccess("user");
    }
}
