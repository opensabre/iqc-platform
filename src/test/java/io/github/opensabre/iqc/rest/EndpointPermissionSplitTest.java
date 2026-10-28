package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.boot.rest.MappingInfoHandler;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.*;
import io.github.opensabre.iqc.modelprofile.*;
import io.github.opensabre.iqc.privacy.*;
import io.github.opensabre.iqc.quality.*;
import io.github.opensabre.iqc.result.*;
import io.github.opensabre.iqc.skill.IqcSkillService;
import io.github.opensabre.iqc.task.InspectionTaskService;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MVC registration and controlled current-grant rejection before owning service calls. */
class EndpointPermissionSplitTest {
    private static final String MANIFEST="""
        iqc:model:manage|POST|/api/iqc/agent-assets/models
        iqc:model:update|PUT|/api/iqc/agent-assets/models/{id}
        iqc:model:enable|POST|/api/iqc/agent-assets/models/{id}/enable
        iqc:model:disable|POST|/api/iqc/agent-assets/models/{id}/disable
        iqc:skill:manage|POST|/api/iqc/agent-assets/skills
        iqc:skill:update|PUT|/api/iqc/agent-assets/skills/{id}
        iqc:skill:enable|POST|/api/iqc/agent-assets/skills/{id}/enable
        iqc:skill:disable|POST|/api/iqc/agent-assets/skills/{id}/disable
        iqc:skill:rollback|POST|/api/iqc/agent-assets/skills/{id}/versions/{versionNo}/rollback
        iqc:skill:view|GET|/api/iqc/agent-assets/skills
        iqc:skill:versions:view|GET|/api/iqc/agent-assets/skills/{id}/versions
        iqc:task:view|GET|/api/iqc/tasks
        iqc:task:detail:view|GET|/api/iqc/tasks/{id}
        iqc:task:executions:view|GET|/api/iqc/tasks/{id}/executions
        iqc:task:execute|POST|/api/iqc/tasks/{id}/run
        iqc:task:resume|POST|/api/iqc/tasks/{id}/resume
        iqc:task:pause|POST|/api/iqc/tasks/{id}/pause
        iqc:task:priority|PUT|/api/iqc/tasks/{id}/priority
        iqc:result:view|GET|/api/iqc/results
        iqc:result:detail:view|GET|/api/iqc/results/{id}
        iqc:result:summary:view|GET|/api/iqc/tasks/{id}/result-summary
        iqc:result:labels:view|GET|/api/iqc/tasks/{id}/label-results
        iqc:result:label-insights:view|GET|/api/iqc/tasks/{id}/label-insights
        iqc:result:conversation:view|GET|/api/iqc/tasks/{id}/conversations/{conversationId}/result-detail
        iqc:result:hierarchy:view|GET|/api/iqc/tasks/{id}/conversations/{conversationId}/result-hierarchy
        iqc:result:conversation-history:view|GET|/api/iqc/conversations/{conversationId}/result-detail
        iqc:result:feedback:view|GET|/api/iqc/quality-operations/feedback
        iqc:result:export|GET|/api/iqc/results/export
        iqc:result:labels:export|GET|/api/iqc/tasks/{id}/label-results/export
        iqc:conversation:view|GET|/api/iqc/conversations
        iqc:conversation:detail:view|GET|/api/iqc/conversations/{id}
        iqc:conversation:api-stats:view|GET|/api/iqc/conversations/api-stats
        iqc:conversation:upload|POST|/api/iqc/conversations/import
        iqc:conversation:batch-upload|POST|/api/iqc/conversations/batch-import
        iqc:conversation:zip-upload|POST|/api/iqc/conversations/zip-import
        iqc:conversation:ingest|POST|/api/iqc/conversations/ingest
        iqc:conversation:batch-ingest|POST|/api/iqc/conversations/ingest-batch
        iqc:mcp:manage|POST|/api/iqc/agent-assets/mcps
        iqc:mcp:update|PUT|/api/iqc/agent-assets/mcps/{id}
        iqc:mcp:enable|POST|/api/iqc/agent-assets/mcps/{id}/enable
        iqc:mcp:disable|POST|/api/iqc/agent-assets/mcps/{id}/disable
        iqc:scheme:manage|GET|/api/iqc/schemes
        iqc:scheme:versions:view|GET|/api/iqc/schemes/{id}/versions
        iqc:dashboard:view|GET|/api/iqc/dashboard
        iqc:bootstrap:view|GET|/api/iqc/bootstrap
        iqc:label:view|GET|/api/iqc/labels/tree
        iqc:label:detail:view|GET|/api/iqc/labels/{id}
        iqc:label:manage|POST|/api/iqc/labels
        iqc:label:category:create|POST|/api/iqc/labels/categories
        iqc:label:group:create|POST|/api/iqc/labels/groups
        iqc:label:category:update|PUT|/api/iqc/labels/categories/{id}
        iqc:label:group:update|PUT|/api/iqc/labels/groups/{id}
        iqc:label:update|PUT|/api/iqc/labels/{id}
        iqc:label:values:update|PUT|/api/iqc/labels/{id}/values
        iqc:label:bindings:update|PUT|/api/iqc/labels/{id}/bindings
        iqc:label:disable|POST|/api/iqc/labels/{id}/disable
        iqc:label:group:disable|POST|/api/iqc/labels/groups/{id}/disable
        iqc:label:category:disable|POST|/api/iqc/labels/categories/{id}/disable
        iqc:label-collection:view|GET|/api/iqc/label-collections
        iqc:label-collection:detail:view|GET|/api/iqc/label-collections/{id}
        iqc:label-collection:manage|POST|/api/iqc/label-collections
        iqc:label-collection:update|PUT|/api/iqc/label-collections/{id}
        iqc:label-collection:members:update|PUT|/api/iqc/label-collections/{id}/members
        iqc:label-collection:publish|POST|/api/iqc/label-collections/{id}/publish
        iqc:label-collection:disable|POST|/api/iqc/label-collections/{id}/disable
        iqc:label-candidate:view|GET|/api/iqc/label-candidates
        iqc:label-candidate:similarities:view|GET|/api/iqc/label-candidates/{id}/similarities
        iqc:label-candidate:review|POST|/api/iqc/label-candidates/{id}/approve
        iqc:label-candidate:reject|POST|/api/iqc/label-candidates/{id}/reject
        iqc:label-candidate:merge|POST|/api/iqc/label-candidates/{id}/merge
        """;
    private SensitiveReadAccessService sensitive;
    private List<Object> services;
    private List<Object> controllers;
    @BeforeEach void setup(){
        sensitive=mock(SensitiveReadAccessService.class);
        var projection=new ResultReadProjection(new ObjectMapper());
        var usage=mock(UsageCounterRecorder.class);var tasks=mock(InspectionTaskService.class);
        var executions=mock(InspectionExecutionService.class);var batch=mock(BatchResultQueryService.class);
        var hierarchy=mock(HierarchicalResultService.class);var labels=mock(LabelResultQueryService.class);
        var exports=mock(LabelInsightExportService.class);var reviews=mock(BusinessItemReviewService.class);
        var skills=mock(IqcSkillService.class);var quality=mock(QualityOperationsService.class);
        var reports=mock(BusinessQualityReportService.class);var labelReviews=mock(LabelResultReviewService.class);
        var samples=mock(BusinessReviewSampleService.class);var models=mock(IqcModelProfileService.class);
        var connections=mock(ModelConnectionTestService.class);
        var conversations=mock(io.github.opensabre.iqc.conversation.ConversationQueryService.class);
        var parser=mock(io.github.opensabre.iqc.conversation.TxtConversationParser.class);
        var imports=mock(io.github.opensabre.iqc.conversation.ConversationImportService.class);
        var mcps=mock(io.github.opensabre.iqc.mcp.IqcMcpServerService.class);
        var schemes=mock(io.github.opensabre.iqc.scheme.InspectionSchemeService.class);
        var publication=mock(io.github.opensabre.iqc.scheme.SchemePublicationService.class);
        var schemeTasks=mock(io.github.opensabre.iqc.scheme.SchemeTaskService.class);
        var taxonomy=mock(LabelTaxonomyService.class);var collections=mock(LabelCollectionService.class);
        var candidates=mock(LabelCandidateService.class);
        services=List.of(taxonomy,collections,candidates,conversations,parser,imports,mcps,schemes,publication,schemeTasks,usage,tasks,executions,batch,hierarchy,labels,exports,reviews,skills,quality,reports,labelReviews,samples,models,connections);
        controllers=List.of(new InspectionTaskController(tasks,usage,sensitive,projection),
            new InspectionResultController(executions,usage,batch,hierarchy,labels,exports,reviews,sensitive,projection),
            new IqcSkillController(skills,sensitive,projection),
            new QualityOperationsController(quality,reviews,reports,labelReviews,samples,sensitive,projection),
            new IqcModelProfileController(models,connections),
            new ConversationQueryController(conversations,sensitive),
            new ConversationImportController(parser,imports,usage,new io.github.opensabre.iqc.conversation.ConversationUploadProperties()),
            new IqcMcpServerController(mcps),new IqcBootstrapController(usage),
            new InspectionSchemeController(schemes,publication,schemeTasks,sensitive,projection),
            new IqcDashboardController(null,null,null,null,labels),
            new LabelController(taxonomy),new LabelCollectionController(collections),new LabelCandidateController(candidates));
    }
    @Test void registrationContainsExactlyOneExpectedEndpointForEachOf70Capabilities(){
        var mvc=MockMvcBuilders.standaloneSetup(controllers.toArray()).build();
        var mapping=mvc.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        var handler=new MappingInfoHandler();ReflectionTestUtils.setField(handler,"requestMappingHandlerMapping",mapping);
        var snapshot=handler.getMappingInfo();
        var expected=MANIFEST.lines().filter(line->!line.isBlank()).toList();assertEquals(70,expected.size());
        for(String line:expected){
            var fields=line.split("\\|");var matches=snapshot.stream().filter(row->fields[0].equals(row.getCode())).toList();
            assertEquals(1,matches.size(),fields[0]);var row=matches.getFirst();
            assertEquals(fields[1],row.getMethod(),fields[0]);assertEquals(fields[2],row.getUrl(),fields[0]);assertTrue(row.isDeclaredPermission());
        }
        var approve=snapshot.stream().filter(row->"iqc:label:approve".equals(row.getCode())).toList();
        assertEquals(1,approve.size());assertEquals("POST",approve.getFirst().getMethod());
        assertEquals("/api/iqc/labels/{id}/publish",approve.getFirst().getUrl());
        verifyNoInteractions(services.toArray());
    }
    static Stream<String> guardedCapabilities(){return MANIFEST.lines().filter(line->!line.isBlank()).map(line->line.split("\\|")[0]).filter(code->!code.startsWith("iqc:label:")&&!code.startsWith("iqc:label-collection:")&&!code.startsWith("iqc:label-candidate:")&&!code.startsWith("iqc:model:")&&!code.startsWith("iqc:mcp:")
            &&!code.equals("iqc:bootstrap:view")&&!code.equals("iqc:dashboard:view")
            &&(!code.startsWith("iqc:conversation:")||Set.of("iqc:conversation:view","iqc:conversation:detail:view","iqc:conversation:api-stats:view").contains(code)));}
    @ParameterizedTest @MethodSource("guardedCapabilities")
    void revokedOrUnrelatedGrantCannotReachOwningService(String code) throws Exception {
        // All requests, including an implementation accidentally checking the old code, fail.
        // The exact verify below catches accidental substitution with view/manage/execute.
        when(sensitive.current(anySet())).thenThrow(IqcException.accessDenied("revoked"));
        var controller=controllers.stream().filter(value->Arrays.stream(value.getClass().getMethods()).anyMatch(method->{
            var permission=method.getAnnotation(ResourcePermission.class);return permission!=null&&permission.code().equals(code);
        })).findFirst().orElseThrow();
        var method=Arrays.stream(controller.getClass().getMethods()).filter(value->{
            var permission=value.getAnnotation(ResourcePermission.class);return permission!=null&&permission.code().equals(code);
        }).findFirst().orElseThrow();
        Object[] args=Arrays.stream(method.getParameterTypes()).map(type->type==long.class?Long.valueOf(1):type==int.class?Integer.valueOf(1):type==String.class?"fixture":null).toArray();
        var failure=assertThrows(InvocationTargetException.class,()->method.invoke(controller,args));
        assertInstanceOf(IqcException.class,failure.getCause());verify(sensitive).current(Set.of(code));
        verifyNoInteractions(services.toArray());
    }
}
