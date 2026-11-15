package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.*;
import io.github.opensabre.iqc.governance.*;
import io.github.opensabre.iqc.rule.*;
import io.github.opensabre.iqc.rule.dao.*;
import io.github.opensabre.iqc.rule.dls.DlsImportService;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.security.IqcResourceServerConfiguration;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.security.config.InternalTokenProperties;
import io.github.opensabre.security.context.InternalTokenUserContext;
import io.github.opensabre.security.token.InternalTokenService;
import io.github.opensabre.security.webmvc.InternalTokenAuthenticationFilter;
import io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice;
import io.github.opensabre.webmvc.rest.RestResponseBodyAdvice;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises IQC's production servlet security and scope checks, without a gateway or database. */
class RuleLifecycleWebSecurityTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    QualityRuleMapper rules;
    QualityRuleVersionMapper versions;
    RuleReferenceGuard references;

    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        rules = context.getBean(QualityRuleMapper.class);
        versions = context.getBean(QualityRuleVersionMapper.class);
        references = context.getBean(RuleReferenceGuard.class);
        var rule = new QualityRule();
        rule.setId("rule"); rule.setCreatedBy("another-user"); rule.setStatus("PUBLISHED");
        when(rules.lockById("rule")).thenReturn(rule);
        when(context.getBean(JwtDecoder.class).decode(any())).thenAnswer(call -> {
            String token = call.getArgument(0);
            return Jwt.withTokenValue(token).header("alg", "RS256").subject("caller")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                    .claim("roles", List.of(token.equals("admin") ? "ADMIN" : "USER")).build();
        });
    }

    @AfterEach void stop() {
        context.close();
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    MockHttpServletRequestBuilder request(boolean remove) {
        return remove ? delete("/api/iqc/config/rules/rule") : post("/api/iqc/config/rules/rule/disable");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anonymousRequestsStopBeforeBusinessServices(boolean remove) throws Exception {
        mvc.perform(request(remove)).andExpect(status().isUnauthorized());
        verifyNoInteractions(rules, versions, references);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void authenticatedCallerOutsideDataScopeCannotMutate(boolean remove) throws Exception {
        mvc.perform(request(remove).header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("IQC-1005"))
                .andExpect(jsonPath("$.mesg").value(org.hamcrest.Matchers.containsString("无权管理该规则")))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(rules).lockById("rule");
        verifyNoMoreInteractions(rules);
        verifyNoInteractions(versions, references);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void referenceRejectionPreservesActionableReason(boolean remove) throws Exception {
        doThrow(IqcException.invalidState("规则仍被引用，不能删除或停用"))
                .when(references).requireUnreferenced("rule");
        mvc.perform(request(remove).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("IQC-1006"))
                .andExpect(jsonPath("$.mesg").value(org.hamcrest.Matchers.containsString("规则仍被引用，不能删除或停用")))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(rules).lockById("rule");
        verifyNoMoreInteractions(rules);
        verifyNoInteractions(versions);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void authorizedUnreferencedRuleReachesMutation(boolean remove) throws Exception {
        mvc.perform(request(remove).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("000000"));
        verify(references).requireUnreferenced("rule");
        if (remove) {
            verify(versions).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
            verify(rules).deleteById("rule");
        } else {
            verify(rules).updateById(org.mockito.ArgumentMatchers.<QualityRule>argThat(rule -> "DISABLED".equals(rule.getStatus())));
            verifyNoInteractions(versions);
        }
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity
    @Import({IqcResourceServerConfiguration.class, QualityConfigController.class,
            IqcExceptionHandlerAdvice.class, DefaultWebMvcExceptionHandlerAdvice.class, RestResponseBodyAdvice.class})
    static class WebConfig {
        @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
        @Bean QualityRuleMapper rules() { return mock(QualityRuleMapper.class); }
        @Bean QualityRuleVersionMapper versions() { return mock(QualityRuleVersionMapper.class); }
        @Bean RuleReferenceGuard references() { return mock(RuleReferenceGuard.class); }
        @Bean QualityRuleService ruleService(QualityRuleMapper rules, QualityRuleVersionMapper versions, RuleReferenceGuard refs) {
            return new QualityRuleService(rules, versions, new ObjectMapper(), refs, new IqcDataScope(null));
        }
        @Bean QualityAgentService agents() { return mock(QualityAgentService.class); }
        @Bean AgentEffectService effects() { return mock(AgentEffectService.class); }
        @Bean QualityRuleSetService sets() { return mock(QualityRuleSetService.class); }
        @Bean DlsImportService imports() { return mock(DlsImportService.class); }
        @Bean InternalTokenAuthenticationFilter internalTokenFilter() {
            return new InternalTokenAuthenticationFilter(mock(InternalTokenService.class),
                    new InternalTokenUserContext(new tools.jackson.databind.ObjectMapper()), new InternalTokenProperties(), "iqc-platform");
        }
    }
}
