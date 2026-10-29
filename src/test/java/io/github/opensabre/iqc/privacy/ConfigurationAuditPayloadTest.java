package io.github.opensabre.iqc.privacy;

import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.eda.api.EdaEvent;
import io.github.opensabre.eda.api.EdaEventPublisher;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.aspect.AuditAspect;
import io.github.opensabre.governance.audit.entity.AuditInfo;
import io.github.opensabre.iqc.rest.IqcModelProfileController;
import io.github.opensabre.iqc.rest.QualityConfigController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real framework audit advice and controller metadata; the domain invocation and local event sink are isolated. */
class ConfigurationAuditPayloadTest {
    private static final String SECRET = "PROMPT_EXPRESSION_SECRET_REF_CANARY";

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REGEX", "STRUCTURED", "DLS"})
    void realRuleValidationDoesNotCopyExpressionIntoAuditError(String ruleType) throws Throwable {
        var rules = mock(io.github.opensabre.iqc.rule.dao.QualityRuleMapper.class);
        var versions = mock(io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper.class);
        var service = new io.github.opensabre.iqc.rule.QualityRuleService(rules, versions,
                new com.fasterxml.jackson.databind.ObjectMapper());
        var access = mock(SensitiveReadAccessService.class);
        when(access.current(java.util.Set.of("iqc:rule:manage"))).thenReturn(new SensitiveReadAccessService.Access(
                "test",true,true,true,java.time.Instant.now()));
        var controller = new QualityConfigController(null, null, service, null, null, access,
                new ResultReadProjection(new com.fasterxml.jackson.databind.ObjectMapper()));
        var request = new QualityConfigController.RuleRequest("Name", "code", "CUSTOM", ruleType,
                "ALL", SECRET + "[", null, 10, "LOW", false);
        var method = QualityConfigController.class.getMethod("createRule", QualityConfigController.RuleRequest.class);
        var invocation = mock(ProceedingJoinPoint.class);
        when(invocation.getArgs()).thenReturn(new Object[]{request});
        when(invocation.proceed()).thenAnswer(call -> controller.createRule(request));
        var publisher = mock(EdaEventPublisher.class);
        var event = new AtomicReference<EdaEvent<?>>();
        doAnswer(call -> { event.set(call.getArgument(0)); return null; }).when(publisher).publishLocal(any());
        var json = JsonMapper.builder().build();
        var aspect = new AuditAspect(json, publisher);
        var failure = assertThrows(io.github.opensabre.iqc.governance.IqcException.class,
                () -> aspect.around(invocation, method.getAnnotation(Audit.class)));
        assertTrue(failure.getMessage().startsWith("规则表达式无效"));
        assertFalse(failure.getMessage().contains(SECRET));
        if (ruleType.equals("REGEX")) {
            var parserError = assertInstanceOf(java.util.regex.PatternSyntaxException.class, failure.getCause());
            assertTrue(parserError.getMessage().contains(SECRET), "Confirm the real parser would leak the expression");
            assertTrue(failure.getMessage().contains("位置 " + parserError.getIndex()));
        }
        assertNotNull(event.get());
        var info = assertInstanceOf(AuditInfo.class, event.get().payload());
        assertEquals(failure.getMessage(), info.getErrorMessage());
        assertEquals("", info.getRequest());
        assertEquals("", info.getResponse());
        assertFalse(json.writeValueAsString(event.get()).contains(SECRET));
        verifyNoInteractions(rules, versions);
    }

    static Stream<Arguments> operations() {
        var config = Stream.of("createAgent", "submitAgent", "publishAgent", "rejectAgent", "disableAgent",
                "createAgentVersion", "rollbackAgent", "createRule", "importDls", "submitRule", "publishRule",
                "rejectRule", "createRuleVersion", "createRuleSet", "createRuleSetVersion", "submitRuleSet",
                "publishRuleSet", "rejectRuleSet").map(name -> operation(QualityConfigController.class, name));
        var models = Stream.of("create", "update", "enable", "disable")
                .map(name -> operation(IqcModelProfileController.class, name));
        return Stream.concat(config, models).flatMap(method -> Stream.of(
                Arguments.of(method, false), Arguments.of(method, true)));
    }

    private static Method operation(Class<?> owner, String name) {
        return Arrays.stream(owner.getDeclaredMethods()).filter(method -> method.getName().equals(name))
                .findFirst().orElseThrow();
    }

    @ParameterizedTest(name = "{0}, domainFailure={1}")
    @MethodSource("operations")
    void auditAdviceOmitsBodiesButPreservesActorTargetAndOutcome(Method method, boolean domainFailure) throws Throwable {
        var audit = method.getAnnotation(Audit.class);
        assertNotNull(audit, "Every named configuration mutation must retain audit coverage");
        assertFalse(audit.request());
        assertFalse(audit.response());
        var payload = Map.of("prompt", SECRET, "expression", SECRET, "secretRef", SECRET);
        var names = Arrays.stream(method.getParameters()).map(parameter -> parameter.getName()).toArray(String[]::new);
        Object[] args = Arrays.stream(names).map(name -> name.equals("id") ? "asset-42"
                : name.equals("versionNo") ? Integer.valueOf(7) : payload).toArray();
        var signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(names);
        var invocation = mock(ProceedingJoinPoint.class);
        when(invocation.getSignature()).thenReturn(signature);
        when(invocation.getArgs()).thenReturn(args);
        var problem = new IllegalStateException("configuration state conflict");
        if (domainFailure) when(invocation.proceed()).thenThrow(problem);
        else when(invocation.proceed()).thenReturn(payload);
        var event = new AtomicReference<EdaEvent<?>>();
        var publisher = mock(EdaEventPublisher.class);
        doAnswer(call -> { event.set(call.getArgument(0)); return null; }).when(publisher).publishLocal(any());
        var json = JsonMapper.builder().build();
        var aspect = new AuditAspect(json, publisher);
        var request = new MockHttpServletRequest("POST", "/api/iqc/audit-validation/" + method.getName());
        request.addHeader("User-Agent", "audit-regression");
        request.setContent(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserContextHolder.getInstance().setContext(Map.of("user_name", "auditor"));
        try {
            if (domainFailure) assertSame(problem, assertThrows(IllegalStateException.class,
                    () -> aspect.around(invocation, audit)));
            else assertSame(payload, aspect.around(invocation, audit));
            verify(invocation).proceed();
            verify(publisher).publishLocal(any());
            assertNotNull(event.get());
            var info = assertInstanceOf(AuditInfo.class, event.get().payload());
            assertEquals("", info.getRequest());
            assertEquals("", info.getResponse());
            assertEquals("auditor", info.getOperatorUsername());
            assertEquals(audit.operationType(), info.getOperationType());
            assertEquals(audit.module(), info.getModule());
            assertEquals(audit.description(), info.getDescription());
            assertEquals(request.getRequestURI(), info.getRequestUrl());
            assertEquals("POST", info.getRequestMethod());
            assertEquals("audit-regression", info.getUserAgent());
            assertNotNull(info.getOperationTime());
            assertTrue(info.getExecutionTime() >= 0);
            assertEquals(domainFailure ? problem.getMessage() : "", info.getErrorMessage());
            boolean hasId = Arrays.asList(names).contains("id");
            assertEquals(hasId ? json.writeValueAsString(method.getName().equals("rollbackAgent")
                    ? "asset-42:7" : "asset-42") : "", info.getTargetKey());
            assertFalse(json.writeValueAsString(event.get()).contains(SECRET));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            UserContextHolder.getInstance().clear();
        }
    }
}
