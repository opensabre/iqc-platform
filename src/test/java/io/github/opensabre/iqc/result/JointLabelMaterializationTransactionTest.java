package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.IqcPlatformApplicationTest;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.ratelimit.RateLimitDecision;
import io.github.opensabre.security.token.InternalTokenConstants;
import io.github.opensabre.security.token.InternalTokenRequest;
import io.github.opensabre.security.token.InternalTokenService;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.label.LabelResolutionService;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.LabelRuleBinding;
import io.github.opensabre.iqc.label.model.LabelValueDefinition;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.dao.InspectionEvidenceMapper;
import io.github.opensabre.iqc.result.dao.RuleInspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scheme.SchemeDependencyResolver;
import io.github.opensabre.iqc.scheme.SchemePublicationService;
import io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.TaskItem;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the real transaction proxy and MyBatis mappers without touching a shared database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.config.import=optional:",
        "spring.datasource.url=jdbc:h2:mem:iqc_joint_tx;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "iqc.task.scheduler-enabled=false",
        "opensabre.governance.dictionary.registration-enabled=false",
        "opensabre.governance.error-catalog.enabled=false",
        "opensabre.resource-registration.enabled=false",
        "opensabre.security.internal-token.enabled=true",
        "opensabre.security.internal-token.active-key-id=test-key",
        "opensabre.security.internal-token.active-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "opensabre.security.internal-token.allowed-issuers[0]=iqc-platform",
        "jetcache.remote.default.type=mock",
        "jetcache.remote.longTime.type=mock",
        "jetcache.remote.shortTime.type=mock"
})
@Import(IqcPlatformApplicationTest.InternalTokenNacosTestConfiguration.class)
class JointLabelMaterializationTransactionTest {
    @LocalServerPort private int httpPort;
    @Autowired private InternalTokenService internalTokens;
    @MockitoBean private IqcOrganizationClient httpOrganization;
    @MockitoBean private GovernanceRateLimiter httpRateLimiter;
    @MockitoBean
    private UsageCounterRecorder httpUsage;
    /** Explicit MySQL subclass retains the migrated schema instead of the fast H2 fixture DDL. */
    protected boolean usesMigratedSchema() { return false; }
    private void insertFixtureMessage(String id, String conversationId, int sequence, String role, String content) {
        jdbc.update("INSERT INTO iqc_conversation_message (id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) "
                + "VALUES (?,?,?,?,?,?,?,?)", id, conversationId, sequence, role, content,
                java.sql.Time.valueOf("00:00:00"), content, sequence);
    }
    @Autowired private HierarchicalResultService results;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ConversationMessageMapper messages;
    @Autowired private ConversationMapper conversations;
    @Autowired private InspectionTaskService taskService;
    @Autowired private InspectionResultMapper observations;
    @Autowired private ConversationInspectionResultMapper canonicalResults;
    @Autowired private InspectionLabelResultMapper persistedLabels;
    @Autowired private InspectionEvidenceMapper persistedEvidence;
    @Autowired private RuleInspectionResultMapper persistedRuleResults;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private InspectionTaskMapper taskMapper;
    @Autowired private TaskExecutionMapper executionMapper;
    @Autowired private TaskItemMapper taskItemMapper;
    @Autowired private QualityRuleMapper ruleMapper;
    @Autowired private QualityRuleVersionMapper ruleVersionMapper;
    @Autowired private InspectionSchemeMapper schemeMapper;
    @Autowired private io.github.opensabre.iqc.scheme.dao.InspectionSchemeVersionMapper schemeVersionMapper;
    @Autowired private io.github.opensabre.iqc.quality.dao.ResultReviewMapper persistedReviews;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @BeforeEach
    void createIsolatedResultTables() {
        // Fast H2 fixture for the existing taxonomy; MySQL subclass uses the formal V1.1.24 tables.
        for (String table : List.of("iqc_label_rule_binding", "iqc_label_value_definition", "iqc_label",
                "iqc_label_group", "iqc_label_category")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        String audit = ",created_by varchar(128),created_time timestamp,updated_by varchar(128),updated_time timestamp";
        jdbc.execute("CREATE TABLE iqc_label_category (id varchar(64) PRIMARY KEY,name varchar(50),code varchar(64),"
                + "prompt varchar(500),max_child_count int,allow_auto_expand boolean,status varchar(32),version_no int,owner_group_id varchar(64)" + audit + ")");
        jdbc.execute("CREATE TABLE iqc_label_group (id varchar(64) PRIMARY KEY,category_id varchar(64),name varchar(50),code varchar(64),"
                + "description varchar(200),max_child_count int,allow_auto_expand boolean,status varchar(32),version_no int,owner_group_id varchar(64)" + audit + ")");
        jdbc.execute("CREATE TABLE iqc_label (id varchar(64) PRIMARY KEY,group_id varchar(64),name varchar(50),code varchar(128),"
                + "description varchar(500),target_role varchar(32),weight decimal(6,2),status varchar(32),version_no int,source_type varchar(32),owner_group_id varchar(64)" + audit + ")");
        jdbc.execute("CREATE TABLE iqc_label_rule_binding (id varchar(64) PRIMARY KEY,label_id varchar(64),rule_id varchar(64),"
                + "rule_version_no int,binding_role varchar(16),display_order int" + audit + ")");
        jdbc.execute("CREATE TABLE iqc_label_value_definition (id varchar(64) PRIMARY KEY,label_id varchar(64),value_code varchar(64),"
                + "value_type varchar(32),description varchar(500),display_order int,config_json clob" + audit + ")");
        jdbc.execute("DROP TABLE IF EXISTS iqc_result_review");
        // Existing review model after V1.1.28/V1.1.30, including lifecycle and revision uniqueness.
        jdbc.execute("""
                CREATE TABLE iqc_result_review (
                  id varchar(64) PRIMARY KEY, result_id varchar(64), target_type varchar(32) NOT NULL,
                  business_result_id varchar(64), label_result_id varchar(64), review_revision int,
                  source_hash varchar(64), request_fingerprint varchar(64), request_comment varchar(1000),
                  decision_fingerprint varchar(64), reviewed_result_json clob, status varchar(32) NOT NULL,
                  original_status varchar(32) NOT NULL, original_score int, original_risk_level varchar(32),
                  final_status varchar(32), final_score int, final_risk_level varchar(32),
                  review_comment varchar(1000), reviewer_id varchar(128), reviewed_time timestamp,
                  owner_group_id varchar(64), created_by varchar(128), created_time timestamp,
                  updated_by varchar(128), updated_time timestamp,
                  UNIQUE (result_id), UNIQUE (business_result_id, review_revision),
                  UNIQUE (label_result_id, review_revision))
                """);
        jdbc.execute("DROP TABLE IF EXISTS iqc_task_item");
        jdbc.execute("DROP TABLE IF EXISTS iqc_task_execution");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_task");
        jdbc.execute("DROP TABLE IF EXISTS iqc_conversation");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_scheme");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_scheme_version");
        jdbc.execute("DROP TABLE IF EXISTS iqc_quality_rule_version");
        jdbc.execute("DROP TABLE IF EXISTS iqc_quality_rule");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_result");
        jdbc.execute("DROP TABLE IF EXISTS iqc_conversation_message");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_label_result");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_evidence");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_rule_result");
        jdbc.execute("DROP TABLE IF EXISTS iqc_inspection_conversation_result");
        jdbc.execute("""
                CREATE TABLE iqc_inspection_scheme (
                  id varchar(64) PRIMARY KEY, name varchar(100), code varchar(64), description varchar(1000),
                  business_scene varchar(100), draft_config_json clob, draft_revision int,
                  active_published_version int, status varchar(32), owner_group_id varchar(64),
                  source_scheme_id varchar(64), source_scheme_name varchar(100), source_scheme_code varchar(64),
                  source_version_no int, source_content_hash varchar(64),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_quality_rule (
                  id varchar(64) PRIMARY KEY, name varchar(128), code varchar(128), category varchar(64),
                  rule_type varchar(32), target_role varchar(32), expression clob, description varchar(500),
                  deduction int, risk_level varchar(32), veto boolean, version_no int, status varchar(32),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_quality_rule_version (
                  id varchar(64) PRIMARY KEY, rule_id varchar(64), version_no int, name varchar(128),
                  code varchar(128), category varchar(64), rule_type varchar(32), target_role varchar(32),
                  expression clob, description varchar(500), deduction int, risk_level varchar(32),
                  veto boolean, status varchar(32), created_by varchar(128), created_time timestamp,
                  updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_conversation (
                  id varchar(64) PRIMARY KEY, batch_no varchar(64), source_type varchar(32),
                  external_id varchar(128), employee_id varchar(128), employee_name varchar(128),
                  employee_group_id varchar(64), customer_external_id varchar(128), customer_name varchar(128),
                  customer_contact_masked varchar(128), channel varchar(32), started_time timestamp,
                  ended_time timestamp, business_type varchar(64), business_no varchar(128), tags_json clob,
                  source_file_name varchar(255), source_fingerprint varchar(128), message_count int,
                  error_count int, ignored_blank_lines int, status varchar(32), owner_group_id varchar(64),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_conversation_message (
                  id varchar(64) PRIMARY KEY, conversation_id varchar(64), sequence_no int,
                  speaker_role varchar(32), relative_time time, content clob, raw_line clob, line_number int,
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_inspection_task (
                  id varchar(64) PRIMARY KEY, conversation_id varchar(64), conversation_ids_json clob,
                  selection_filter_json clob, concurrency_limit int, scheduled_time timestamp,
                  name varchar(255), task_type varchar(32), agent_id varchar(64), rule_set_id varchar(64),
                  rule_ids_json clob, agent_snapshot_json clob, rule_snapshot_json clob,
                  label_scope_snapshot_json clob, run_count int, confidence_threshold decimal(5,4),
                  auto_expand_enabled boolean, auto_expand_prompt varchar(1000), queue_priority bigint,
                  pause_requested boolean, cancel_requested boolean, status varchar(32),
                  total_messages int, processed_messages int, failed_messages int,
                  current_execution_id varchar(64), attempt_count int, owner_group_id varchar(64),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_task_execution (
                  id varchar(64) PRIMARY KEY, task_id varchar(64), attempt_no int, status varchar(32),
                  processed_messages int, failed_messages int, error_message varchar(1000),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_task_item (
                  id varchar(64) PRIMARY KEY, task_id varchar(64), execution_id varchar(64),
                  conversation_id varchar(64), message_id varchar(64), sequence_no int, status varchar(32),
                  result_id varchar(64), attempt_count int, error_message varchar(1000),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_inspection_result (
                  id varchar(64) PRIMARY KEY, task_id varchar(64), execution_id varchar(64),
                  conversation_id varchar(64), message_id varchar(64), rule_id varchar(64),
                  speaker_role varchar(32), result_status varchar(32), score int,
                  risk_level varchar(32), deduction int, reason varchar(500), evidence clob,
                  finding_json clob, evidence_json clob, suggestion_json clob, rule_breakdown_json clob,
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_inspection_conversation_result (
                  id varchar(64) PRIMARY KEY, task_id varchar(64), execution_id varchar(64), conversation_id varchar(64),
                  aggregation_mode varchar(16), result_status varchar(32), score int, final_score decimal(8,2),
                  score_status varchar(32), scoring_result_json clob, business_item_results_json clob,
                  risk_level varchar(32), deduction int, reason varchar(500),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp,
                  UNIQUE (execution_id, conversation_id))
                """);
        jdbc.execute("""
                CREATE TABLE iqc_inspection_rule_result (
                  id varchar(64) PRIMARY KEY, conversation_result_id varchar(64), rule_id varchar(64),
                  rule_version_no int, rule_type varchar(32), evaluation_scope varchar(32), result_status varchar(32),
                  score int, risk_level varchar(32), deduction int, reason varchar(500), confidence decimal(5,4),
                  finding_json clob, created_by varchar(128), created_time timestamp,
                  updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_inspection_evidence (
                  id varchar(64) PRIMARY KEY, rule_result_id varchar(64), message_id varchar(64), sequence_no int,
                  internal_definition varchar(256), evidence_type varchar(32), matched_text clob,
                  start_offset int, end_offset int, created_by varchar(128), created_time timestamp,
                  updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.execute("""
                CREATE TABLE iqc_inspection_label_result (
                  id varchar(64) PRIMARY KEY, conversation_result_id varchar(64), label_id varchar(64),
                  label_version_no int, value_code varchar(64), value_json clob, confidence decimal(5,4),
                  source_rule_result_id varchar(64), generation_source varchar(32),
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp,
                  UNIQUE (conversation_result_id, label_id, value_code))
                """);
    }

    @Test
    void materializesOneCanonicalResultAndDoesNotDuplicateItOnSameExecutionRetry() {
        var task = task();
        var first = results.materialize(task, "execution-1", snapshot(), List.of(message()), List.of(observation()));
        var retry = results.materialize(task, "execution-1", snapshot(), List.of(message()), List.of(observation()));

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(first.getFinalScore()).isEqualByComparingTo("100");
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_evidence")).isEqualTo(1);
        assertThat(count("iqc_inspection_label_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
    }

    @Test
    void labelOnlySchemePersistsCoverageWithoutACompatibilityOrFinalScore() throws Exception {
        var task = task();
        var first = results.materialize(task, "execution-label-only", labelOnlySnapshot(),
                List.of(message()), List.of(observation()));
        var retry = results.materialize(task, "execution-label-only", labelOnlySnapshot(),
                List.of(message()), List.of(observation()));

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(first.getScore()).isNull();
        assertThat(first.getFinalScore()).isNull();
        assertThat(first.getScoreStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(mapper.readTree(first.getBusinessItemResultsJson())).isEmpty();
        assertThat(mapper.readTree(first.getScoringResultJson()).path("lines")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT score FROM iqc_inspection_conversation_result", Integer.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result", java.math.BigDecimal.class)).isNull();
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_evidence")).isEqualTo(1);
        assertThat(count("iqc_inspection_label_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
    }

    @Test
    void labelOnlyMissingObservationIsAnErrorInsteadOfAFalseOrSuccessfulCoverage() throws Exception {
        var canonical = results.materialize(task(), "execution-label-missing", labelOnlySnapshot(),
                List.of(message()), List.of());

        assertThat(canonical.getScore()).isNull();
        assertThat(canonical.getFinalScore()).isNull();
        assertThat(canonical.getScoreStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(jdbc.queryForObject("SELECT result_status FROM iqc_inspection_rule_result", String.class))
                .isEqualTo("NOT_EVALUATED");
        var value = mapper.readTree(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class));
        assertThat(value.path("status").asText()).isEqualTo("ERROR");
        assertThat(value.has("value")).isFalse();
        assertThat(value.path("reasons").toString()).contains("DETECTION_INCOMPLETE");
    }

    @Test
    void labelOnlyExecutionWritesObservationCanonicalDecisionAndFactInOneLocalDatabase() throws Exception {
        var task = task(); task.setStatus("RUNNING"); task.setCurrentExecutionId("execution-integrated");
        var snapshot = labelOnlySnapshot().deepCopy();
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("rules").get(0);
        rule.put("ruleType", "REGEX").put("expression", "没有房子");
        var target = (com.fasterxml.jackson.databind.node.ObjectNode) rule.path("labelFactTargets").get(0);
        target.put("valueCode", "owns").put("subjectRole", "customer").put("onRuleHitValue", false);
        task.setRuleSnapshotJson(snapshot.toString());
        insertFixtureMessage("message-1", "conversation-1", 1, "customer", "我没有房子");
        var item = new TaskItem(); item.setId("item-1"); item.setTaskId(task.getId());
        item.setExecutionId("execution-integrated"); item.setConversationId("conversation-1");
        item.setMessageId("message-1"); item.setStatus("PENDING"); item.setAttemptCount(0);
        var tasks = mock(InspectionTaskMapper.class);
        var llm = mock(io.github.opensabre.iqc.result.llm.LlmQualityProvider.class);
        when(tasks.selectById(task.getId())).thenReturn(task);
        var execution = new InspectionExecutionService(tasks, mock(ConversationMapper.class), messages, observations,
                mapper, mock(TaskExecutionMapper.class), mock(TaskItemMapper.class),
                mock(io.github.opensabre.iqc.shared.IqcDataScope.class), llm, mock(UsageCounterRecorder.class),
                results, mock(io.github.opensabre.iqc.label.LabelCandidateService.class),
                mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));

        ReflectionTestUtils.invokeMethod(execution, "processConversation", task, "execution-integrated", snapshot, List.of(item));

        assertThat(item.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(count("iqc_inspection_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score FROM iqc_inspection_result", Integer.class)).isNull();
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result", String.class))
                .isEqualTo("NOT_APPLICABLE");
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result", java.math.BigDecimal.class))
                .isNull();
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_evidence")).isEqualTo(1);
        assertThat(count("iqc_inspection_label_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
        verifyNoInteractions(llm);
    }

    private TrialHarness newDraftTrial() throws Exception {
        return newDraftTrial(false, false);
    }

    /** Uses the real HTTP controller, signed identity, task services and async worker against the same fixture. */
    @Test
    void signedHttpJointTrialFreezesConfigurationAndRunsWithoutDuplicateTasks() throws Exception {
        var harness = newDraftTrial(true, false, true);
        jdbc.update("DELETE FROM iqc_inspection_task WHERE id = ?", harness.task().getId());
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_config_json=?,created_by='alice',owner_group_id='g1' WHERE id='scheme-1'",
                mapper.writeValueAsString(harness.release().definition()));
        jdbc.update("UPDATE iqc_conversation SET created_by='alice',owner_group_id='g1' WHERE id='conversation-1'");
        jdbc.update("UPDATE iqc_conversation SET created_by='eve',owner_group_id='g2' WHERE id='conversation-2'");
        when(httpOrganization.getUserByUniqueId(anyString())).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("eve", "Eve", "eve", "g2", "Group 2")));
        when(httpOrganization.getUserByUniqueId("alice")).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("alice", "Alice", "alice", "g1", "Group 1")));
        when(httpRateLimiter.check(any())).thenReturn(new RateLimitDecision(
                true, 60, 0, 60, null, "isolated-test"));
        jdbc.update("INSERT INTO iqc_label_category (id,name,code,status,version_no,created_by,owner_group_id) VALUES ('profile','画像','profile','PUBLISHED',1,'alice','g1')");
        jdbc.update("INSERT INTO iqc_label_group (id,category_id,name,code,status,version_no,allow_auto_expand,created_by,owner_group_id) VALUES ('assets','profile','资产','assets','PUBLISHED',1,false,'alice','g1')");
        jdbc.update("INSERT INTO iqc_label (id,group_id,name,code,target_role,weight,status,version_no,created_by,owner_group_id) VALUES ('house','assets','是否有房','house','customer',1,'PUBLISHED',1,'alice','g1')");
        jdbc.update("INSERT INTO iqc_label_rule_binding (id,label_id,rule_id,rule_version_no,binding_role,display_order) VALUES ('house-binding','house','rule-1',1,'PRIMARY',0)");
        jdbc.update("INSERT INTO iqc_label_value_definition (id,label_id,value_code,value_type,display_order,config_json) VALUES ('house-value','house','owns','BOOLEAN',0,?)",
                "{\"onRuleHit\":{\"rule-1\":false}}");
        String alice = signedHttpIdentity("alice");
        String path = "/api/iqc/schemes/scheme-1/trials";
        String request = "{\"expectedRevision\":1,\"conversationIds\":[\"conversation-1\"],\"requestId\":\"joint_http_trial_01\"}";
        assertThat(http(null, path, request).statusCode()).isEqualTo(401);
        assertHttpBusinessDenied(http(signedHttpIdentity("eve"), path, request));
        assertHttpBusinessDenied(http(alice, path,
                request.replace("conversation-1", "conversation-2").replace("trial_01", "trial_02")));
        assertThat(count("iqc_inspection_task")).isZero();
        jdbc.update("UPDATE iqc_label SET created_by='eve',owner_group_id='g2' WHERE id='house'");
        assertHttpBusinessDenied(http(alice, path, request));
        assertThat(count("iqc_inspection_task")).isZero();
        jdbc.update("UPDATE iqc_label SET created_by='alice',owner_group_id='g1' WHERE id='house'");

        JsonNode created = httpData(http(alice, path, request));
        String taskId = created.path("id").asText();
        assertThat(taskId).startsWith("tr-");
        assertThat(created.path("createdBy").asText()).isEqualTo("alice");
        assertThat(created.path("ownerGroupId").asText()).isEqualTo("g1");
        JsonNode frozen = mapper.readTree(created.path("ruleSnapshotJson").asText());
        assertThat(frozen.at("/schemeSnapshot/release/definition/schemaVersion").asText()).isEqualTo(SchemeDefinition.JOINT_TASK_SCHEMA);
        assertThat(frozen.at("/schemeSnapshot/contentHash").asText()).isEqualTo(
                io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(frozen.at("/schemeSnapshot/release").toString()));
        assertThat(mapper.readTree(created.path("labelScopeSnapshotJson").asText()))
                .isEqualTo(frozen.at("/schemeSnapshot/release/dependencies/labels"));
        assertThat(frozen.at("/schemeSnapshot/release/dependencies/labels/labels/0/groupName").asText()).isEqualTo("资产");
        assertThat(frozen.at("/schemeSnapshot/release/dependencies/labels/labels/0/categoryName").asText()).isEqualTo("画像");
        assertThat(httpData(http(alice, path, request)).path("id").asText()).isEqualTo(taskId);
        JsonNode changedPayload = mapper.readTree(http(alice, path, request.replace("\"expectedRevision\":1", "\"expectedRevision\":2")).body());
        assertThat(changedPayload.has("data")).isFalse();
        assertThat(changedPayload.path("code").asText()).isEqualTo("IQC-1003");
        assertThat(count("iqc_inspection_task")).isEqualTo(1);

        // Mutating the draft cannot replace the already frozen standard used by the asynchronous worker.
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_config_json='{}',draft_revision=2 WHERE id='scheme-1'");
        jdbc.update("UPDATE iqc_label SET name='当前名称',version_no=2 WHERE id='house'");
        jdbc.update("UPDATE iqc_label_group SET name='当前分组' WHERE id='assets'");
        jdbc.update("UPDATE iqc_label_category SET name='当前分类' WHERE id='profile'");
        jdbc.update("UPDATE iqc_label_value_definition SET config_json=? WHERE id='house-value'", "{\"onRuleHit\":{\"rule-1\":true}}");
        httpData(http(alice, "/api/iqc/tasks/" + taskId + "/run", "{}"));
        awaitHttpTask(alice, taskId);
        JsonNode business = httpData(http(alice, "/api/iqc/results/business?taskId=" + taskId, null));
        assertThat(business.path("total").asInt()).isEqualTo(1);
        assertThat(business.path("records").get(0).path("finalScore").decimalValue()).isEqualByComparingTo("90");
        JsonNode labels = httpData(http(alice, "/api/iqc/tasks/" + taskId + "/label-results", null));
        assertThat(labels).hasSize(1);
        assertThat(labels.get(0).path("labelId").asText()).isEqualTo("house");
        assertThat(labels.get(0).path("labelName").asText()).isEqualTo("是否有房");
        assertThat(labels.get(0).path("labelPath").asText()).isEqualTo("画像 / 资产 / 是否有房");
        JsonNode httpValue = mapper.readTree(labels.get(0).path("valueJson").asText());
        assertThat(httpValue.path("status").asText()).isEqualTo("KNOWN");
        assertThat(httpValue.path("value").isBoolean()).isTrue();
        assertThat(httpValue.path("value").asBoolean()).isFalse();
        assertThat(count("iqc_inspection_label_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
        assertHttpBusinessDenied(http(signedHttpIdentity("eve"), "/api/iqc/tasks/" + taskId + "/label-results", null));
    }

    @Test
    void publishedTemplateRunsForAnotherGroupMemberWithFrozenIndependentScoring() throws Exception {
        var harness = newDraftTrial(true);
        jdbc.update("DELETE FROM iqc_inspection_task WHERE id=?", harness.task().getId());
        createIsolatedSchemeVersionTable();
        var source = harness.release().definition();
        var checksOnly = new SchemeDefinition(source.schemaVersion(), source.items(), source.agent(),
                source.scoring(), source.runLimits());
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_config_json=?,created_by='alice',owner_group_id='g1' WHERE id='scheme-1'",
                mapper.writeValueAsString(checksOnly));
        jdbc.update("UPDATE iqc_conversation SET created_by='alice',owner_group_id='g1' WHERE id='conversation-1'");
        when(httpOrganization.getUserByUniqueId(anyString())).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("eve", "Eve", "eve", "g2", "Group 2")));
        for (String user : List.of("alice", "bob"))
            when(httpOrganization.getUserByUniqueId(user)).thenReturn(Result.success(
                    new IqcOrganizationClient.OrganizationUser(user, user, user, "g1", "Group 1")));
        when(httpRateLimiter.check(any())).thenReturn(new RateLimitDecision(true, 60, 0, 60, null, "isolated-test"));
        String alice = signedHttpIdentity("alice");
        String bob = signedHttpIdentity("bob");
        String trialId = httpData(http(alice, "/api/iqc/schemes/scheme-1/trials",
                "{\"expectedRevision\":1,\"conversationIds\":[\"conversation-1\"],\"requestId\":\"published_trial_01\"}")).path("id").asText();
        httpData(http(alice, "/api/iqc/tasks/" + trialId + "/run", "{}"));
        awaitHttpTask(alice, trialId);
        JsonNode published = httpData(http(alice, "/api/iqc/schemes/scheme-1/publish",
                "{\"expectedRevision\":1,\"trialTaskId\":\"" + trialId + "\",\"resultsReviewed\":true}"));
        assertThat(published.path("versionNo").asInt()).isEqualTo(1);
        assertThat(httpData(http(bob, "/api/iqc/schemes/published", null))).hasSize(1);
        assertThat(httpData(http(signedHttpIdentity("eve"), "/api/iqc/schemes/published", null))).isEmpty();
        String path = "/api/iqc/schemes/scheme-1/versions/1/tasks";
        var scheduledTime = java.time.LocalDateTime.now().plusMinutes(15);
        String scheduledRequest = mapper.writeValueAsString(Map.of(
                "requestId", "published_schedule_01", "name", "到期模板质检", "taskType", "SCHEDULED",
                "conversationIds", List.of(), "scheduledTime", scheduledTime.toString(),
                "selectionFilter", Map.of("status", "IMPORTED", "limit", 10), "concurrency", 1));
        JsonNode scheduled = httpData(http(bob, path, scheduledRequest));
        String scheduledId = scheduled.path("id").asText();
        assertThat(scheduled.path("taskType").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduled.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduled.path("conversationIdsJson").asText()).isEqualTo("[]");
        JsonNode scheduledSnapshot = mapper.readTree(scheduled.path("ruleSnapshotJson").asText());
        assertThat(scheduledSnapshot.path("schemeSnapshot").path("kind").asText()).isEqualTo("PUBLISHED");
        assertThat(scheduledSnapshot.path("schemeSnapshot").path("versionNo").asInt()).isEqualTo(1);
        assertThat(httpData(http(bob, path, scheduledRequest)).path("id").asText()).isEqualTo(scheduledId);
        JsonNode changedSchedule = mapper.readTree(http(bob, path, scheduledRequest.replace("\"limit\":10", "\"limit\":5")).body());
        assertThat(changedSchedule.path("code").asText()).isEqualTo("IQC-1003");
        String request = "{\"requestId\":\"published_task_01\",\"name\":\"日常质检\",\"conversationIds\":[\"conversation-1\"],\"concurrency\":1}";
        int tasksBeforeRejectedOverride = count("iqc_inspection_task");
        HttpResponse<String> rejectedOverride = http(bob, path,
                request.replace("\"concurrency\":1", "\"concurrency\":1,\"overrides\":{\"productName\":\"产品A\"}"));
        assertThat(rejectedOverride.statusCode()).isEqualTo(200);
        JsonNode overrideError = mapper.readTree(rejectedOverride.body());
        assertThat(overrideError.path("code").asText()).isEqualTo("IQC-1003");
        assertThat(overrideError.path("mesg").asText()).contains("overrides");
        assertThat(count("iqc_inspection_task")).isEqualTo(tasksBeforeRejectedOverride);
        JsonNode task = httpData(http(bob, path, request));
        String taskId = task.path("id").asText();
        assertThat(task.path("createdBy").asText()).isEqualTo("bob");
        assertThat(task.path("ownerGroupId").asText()).isEqualTo("g1");
        assertThat(httpData(http(bob, path, request)).path("id").asText()).isEqualTo(taskId);
        assertThat(mapper.readTree(http(bob, path, request.replace("日常质检", "另一任务")).body()).path("code").asText())
                .isEqualTo("IQC-1003");
        assertHttpBusinessDenied(http(signedHttpIdentity("eve"), path, request));
        // A later editable draft must not change the novice task's released standard.
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_config_json='{}',draft_revision=2 WHERE id='scheme-1'");
        httpData(http(bob, "/api/iqc/tasks/" + taskId + "/run", "{}"));
        awaitHttpTask(bob, taskId);
        JsonNode business = httpData(http(bob, "/api/iqc/results/business?taskId=" + taskId, null));
        assertThat(business.path("total").asInt()).isEqualTo(1);
        assertThat(business.path("records").get(0).path("finalScore").decimalValue()).isEqualByComparingTo("90");
        jdbc.update("INSERT INTO iqc_conversation (id, source_type, source_file_name, source_fingerprint, message_count, status) VALUES (?, ?, ?, ?, ?, ?)",
                "conversation-later", "FILE", "later.txt", "later-fingerprint", 1, "IMPORTED");
        jdbc.update("UPDATE iqc_conversation SET created_by='alice',owner_group_id='g1' WHERE id='conversation-later'");
        insertFixtureMessage("message-later", "conversation-later", 1, "customer", "我没有房子");
        var due = taskService.materializeDue(scheduledTime.plusSeconds(1));
        assertThat(due).extracting(InspectionTask::getId).containsExactly(scheduledId);
        var materialized = taskMapper.selectById(scheduledId);
        assertThat(mapper.readTree(materialized.getConversationIdsJson()).size()).isEqualTo(2);
        assertThat(mapper.readTree(materialized.getRuleSnapshotJson()).path("schemeSnapshot").path("release").path("definition")
                .path("items").size()).isEqualTo(1);
        httpData(http(bob, "/api/iqc/tasks/" + scheduledId + "/run", "{}"));
        awaitHttpTask(bob, scheduledId);
        JsonNode scheduledBusiness = httpData(http(bob, "/api/iqc/results/business?taskId=" + scheduledId, null));
        assertThat(scheduledBusiness.path("total").asInt()).isEqualTo(2);
        assertThat(count("iqc_inspection_task")).isEqualTo(3);
        assertThat(count("iqc_inspection_scheme_version")).isEqualTo(1);
        assertThat(count("iqc_inspection_label_result")).isZero();
        assertHttpBusinessDenied(http(signedHttpIdentity("eve"), "/api/iqc/tasks/" + taskId, null));
    }

    private void createIsolatedSchemeVersionTable() {
        if (!usesMigratedSchema()) jdbc.execute("""
                CREATE TABLE iqc_inspection_scheme_version (
                  id varchar(64) PRIMARY KEY, scheme_id varchar(64), version_no int, source_draft_revision int,
                  source_trial_task_id varchar(64), snapshot_json clob, content_hash varchar(64), archived boolean default false not null,
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
    }

    private void awaitHttpTask(String token, String taskId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        JsonNode completed;
        do {
            completed = httpData(http(token, "/api/iqc/tasks/" + taskId, null));
            if (List.of("SUCCEEDED", "FAILED", "PARTIAL_FAILED").contains(completed.path("status").asText())) break;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertThat(completed.path("status").asText()).as(completed.toString()).isEqualTo("SUCCEEDED");
    }

    private String signedHttpIdentity(String username) {
        return internalTokens.issue(new InternalTokenRequest(
                "iqc-platform", username, username, "iqc-platform", List.of(), List.of(), List.of(),
                0, null, null, Map.of()));
    }

    private HttpResponse<String> http(String token, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header(InternalTokenConstants.HEADER, token);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode httpData(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode envelope = mapper.readTree(response.body());
        assertThat(envelope.has("data")).as(response.body()).isTrue();
        return envelope.path("data");
    }

    private void assertHttpBusinessDenied(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode envelope = mapper.readTree(response.body());
        assertThat(envelope.path("code").asText()).isEqualTo("IQC-1005");
        assertThat(envelope.has("data")).isFalse();
    }

    private TrialHarness newDraftTrial(boolean includeScoredCheck) throws Exception {
        return newDraftTrial(includeScoredCheck, false);
    }

    private TrialHarness newDraftTrial(boolean includeScoredCheck, boolean failingLlmLabel) throws Exception {
        return newDraftTrial(includeScoredCheck, failingLlmLabel, false);
    }

    private TrialHarness newDraftTrial(boolean includeScoredCheck, boolean failingLlmLabel,
                                       boolean twoConversations) throws Exception {
        return newDraftTrial(includeScoredCheck, failingLlmLabel, twoConversations, false);
    }

    private TrialHarness newDraftTrial(boolean includeScoredCheck, boolean failingLlmLabel,
                                       boolean twoConversations, boolean twoMessagesInFirstConversation) throws Exception {
        return newDraftTrial(includeScoredCheck, failingLlmLabel, twoConversations,
                twoMessagesInFirstConversation, null);
    }

    private record TrialApplicability(SchemeDefinition.RuleReference rule, String regexExpression) {}

    private TrialHarness newDraftTrial(boolean includeScoredCheck, boolean failingLlmLabel,
                                       boolean twoConversations, boolean twoMessagesInFirstConversation,
                                       TrialApplicability applicability) throws Exception {
        return newDraftTrial(includeScoredCheck, failingLlmLabel, twoConversations, twoMessagesInFirstConversation,
                applicability, false);
    }

    private TrialHarness newDraftTrial(boolean includeScoredCheck, boolean failingLlmLabel,
                                       boolean twoConversations, boolean twoMessagesInFirstConversation,
                                       TrialApplicability applicability, boolean scopedConversationCheck) throws Exception {
        var snapshot = labelOnlySnapshot().deepCopy();
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("rules").get(0);
        rule.put("ruleType", "REGEX").put("expression", "没有房子");
        var labelRule = failingLlmLabel ? mapper.createObjectNode().put("id", "rule-2")
                .put("versionNo", 1).put("ruleType", "LLM").put("targetRole", "customer")
                .put("expression", "识别客户是否有房") : rule;
        if (failingLlmLabel) {
            rule.remove("labelFactTargets");
            labelRule.putArray("labelFactTargets").addObject().put("labelId", "house");
        }
        var target = (com.fasterxml.jackson.databind.node.ObjectNode) labelRule.path("labelFactTargets").get(0);
        target.put("labelVersionNo", 1).put("valueCode", "owns").put("subjectRole", "customer");
        if (!failingLlmLabel) target.put("onRuleHitValue", false);
        var binding = new LabelRuleBinding(); binding.setRuleId(failingLlmLabel ? "rule-2" : "rule-1"); binding.setRuleVersionNo(1);
        var value = new LabelValueDefinition(); value.setValueCode("owns"); value.setValueType("BOOLEAN");
        if (!failingLlmLabel) value.setConfigJson("{\"onRuleHit\":{\"rule-1\":false}}");
        var label = new LabelResolutionService.LabelSnapshot("house", 1, "是否有房", "house", null,
                null, null, null, null, false, "customer", null, List.of(binding), List.of(value));
        var frozen = new LabelResolutionService.ResolvedSelection("2.0", List.of(label),
                List.of(failingLlmLabel ? "rule-2" : "rule-1"));
        var labelOnlyDefinition = mapper.treeToValue(snapshot.at("/schemeSnapshot/release/definition"), SchemeDefinition.class);
        var definition = includeScoredCheck
                ? new SchemeDefinition(SchemeDefinition.SCHEMA,
                    List.of(new SchemeDefinition.Item("house-check", "住房信息核查",
                            new SchemeDefinition.RuleReference("rule-1", 1), SchemeDefinition.HitMeaning.VIOLATION,
                            applicability == null ? null : applicability.rule())),
                    failingLlmLabel ? new SchemeDefinition.AgentReference("agent-1", 1) : null,
                    new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION,
                            100, 60, List.of(new InspectionScoring.Item("house-check", 10, false))),
                    null, labelOnlyDefinition.labels())
                : labelOnlyDefinition;
        if (scopedConversationCheck) {
            labelRule.remove("labelFactTargets");
            labelRule.put("inspectionScope", "CONVERSATION");
            definition = new SchemeDefinition(SchemeDefinition.SCHEMA,
                    List.of(new SchemeDefinition.Item("house-check", "完整会话住房核查",
                            new SchemeDefinition.RuleReference("rule-2", 1), SchemeDefinition.HitMeaning.VIOLATION,
                            null, SchemeDefinition.InputScope.CONVERSATION)),
                    new SchemeDefinition.AgentReference("agent-1", 1), definition.scoring());
        }
        var agentConfig = "{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"识别客户画像\","
                + "\"primaryModelProfileId\":\"model-1\",\"assetSnapshots\":{\"primaryModel\":{\"id\":\"model-1\"}}}";
        var agentSnapshot = failingLlmLabel ? mapper.createObjectNode().put("id", "agent-1")
                .put("versionNo", 1).put("configJson", agentConfig) : null;
        var dependencyRules = new java.util.ArrayList<JsonNode>();
        dependencyRules.add(rule);
        if (failingLlmLabel) dependencyRules.add(labelRule);
        if (applicability != null && applicability.regexExpression() != null) {
            dependencyRules.add(mapper.createObjectNode().put("id", applicability.rule().id())
                    .put("versionNo", applicability.rule().versionNo()).put("ruleType", "REGEX")
                    .put("targetRole", "customer").put("expression", applicability.regexExpression()));
            jdbc.update("INSERT INTO iqc_quality_rule (id, name, code, category, rule_type, target_role, expression, deduction, risk_level, veto, version_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    applicability.rule().id(), "适用条件", "applicability", "CUSTOM", "REGEX", "customer",
                    applicability.regexExpression(), 0, "LOW", false, applicability.rule().versionNo(), "PUBLISHED");
            jdbc.update("INSERT INTO iqc_quality_rule_version (id, rule_id, version_no, name, code, category, rule_type, target_role, expression, deduction, risk_level, veto, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "condition-version", applicability.rule().id(), applicability.rule().versionNo(), "适用条件",
                    "applicability", "CUSTOM", "REGEX", "customer", applicability.regexExpression(),
                    0, "LOW", false, "PUBLISHED");
        }
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                "纯画像试跑", "house", null, "销售", definition,
                new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.Dependencies(
                        dependencyRules, agentSnapshot,
                        failingLlmLabel ? "INDEPENDENT" : "RULE_ONLY", scopedConversationCheck ? null : frozen));
        jdbc.update("INSERT INTO iqc_conversation (id, source_type, source_file_name, source_fingerprint, message_count, status) VALUES (?, ?, ?, ?, ?, ?)",
                "conversation-1", "FILE", "house.txt", "house-test", 1, "IMPORTED");
        insertFixtureMessage("message-1", "conversation-1", 1, "customer", "我没有房子");
        if (twoMessagesInFirstConversation) {
            insertFixtureMessage("message-extra", "conversation-1", 2, "customer", "确实没有房子");
            jdbc.update("UPDATE iqc_conversation SET message_count = 2 WHERE id = 'conversation-1'");
        }
        if (twoConversations) {
            jdbc.update("INSERT INTO iqc_conversation (id, source_type, source_file_name, source_fingerprint, message_count, status) VALUES (?, ?, ?, ?, ?, ?)",
                    "conversation-2", "FILE", "house-2.txt", "house-test-2", 1, "IMPORTED");
            insertFixtureMessage("message-2", "conversation-2", 1, "customer", "我没有房子");
        }
        jdbc.update("INSERT INTO iqc_inspection_scheme (id, name, code, business_scene, draft_config_json, draft_revision, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "scheme-1", "纯画像试跑", "house", "销售", "{}", 1, "ACTIVE");
        jdbc.update("INSERT INTO iqc_quality_rule (id, name, code, category, rule_type, target_role, expression, deduction, risk_level, veto, version_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "rule-1", "无房", "no-house", "CUSTOM", "REGEX", "customer", "没有房子", 0, "LOW", false, 1, "PUBLISHED");
        jdbc.update("INSERT INTO iqc_quality_rule_version (id, rule_id, version_no, name, code, category, rule_type, target_role, expression, deduction, risk_level, veto, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "rule-version-1", "rule-1", 1, "无房", "no-house", "CUSTOM", "REGEX", "customer", "没有房子", 0, "LOW", false, "PUBLISHED");
        if (failingLlmLabel) {
            jdbc.update("INSERT INTO iqc_quality_rule (id, name, code, category, rule_type, target_role, expression, deduction, risk_level, veto, version_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "rule-2", "客户住房画像", "house-profile", "CUSTOM", "LLM", "customer", "识别客户是否有房", 0, "LOW", false, 1, "PUBLISHED");
            jdbc.update("INSERT INTO iqc_quality_rule_version (id, rule_id, version_no, name, code, category, rule_type, target_role, expression, deduction, risk_level, veto, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "rule-version-2", "rule-2", 1, "客户住房画像", "house-profile", "CUSTOM", "LLM", "customer", "识别客户是否有房", 0, "LOW", false, "PUBLISHED");
        }
        var scope = mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
        when(scope.canView(nullable(String.class), nullable(String.class))).thenReturn(true);
        var tasks = new InspectionTaskService(taskMapper, conversations,
                mock(io.github.opensabre.iqc.agent.dao.QualityAgentMapper.class),
                mock(io.github.opensabre.iqc.rule.dao.QualityRuleMapper.class),
                mock(io.github.opensabre.iqc.rule.QualityRuleSetService.class), mapper, executionMapper, scope,
                mock(LabelResolutionService.class));
        var task = tasks.createSchemeTrial("scheme-1", 1, "纯画像试跑",
                twoConversations ? List.of("conversation-1", "conversation-2") : List.of("conversation-1"), release);
        var llm = mock(io.github.opensabre.iqc.result.llm.LlmQualityProvider.class);
        var agents = mock(io.github.opensabre.iqc.agent.dao.QualityAgentMapper.class);
        var agentVersions = mock(io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper.class);
        if (failingLlmLabel) {
            var current = new io.github.opensabre.iqc.agent.model.QualityAgent(); current.setStatus("PUBLISHED");
            when(agents.selectById("agent-1")).thenReturn(current);
            var version = new io.github.opensabre.iqc.agent.model.QualityAgentVersion();
            version.setAgentId("agent-1"); version.setVersionNo(1); version.setStatus("PUBLISHED");
            version.setConfigJson(agentConfig);
            when(agentVersions.selectOne(any())).thenReturn(version);
            if (twoConversations) {
                when(llm.evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                        .thenAnswer(invocation -> {
                            List<ConversationMessage> context = invocation.getArgument(0);
                            return "message-2".equals(context.getFirst().getId())
                                    ? knownFalseHouse("message-2")
                                    : new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(
                                            false, false, "受控模型不可用");
                        });
            } else when(llm.evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                    .thenReturn(new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(
                            false, false, "受控模型不可用"));
        }
        var labelResolver = mock(LabelResolutionService.class);
        var dependencies = new SchemeDependencyResolver(ruleMapper, ruleVersionMapper,
                agents, agentVersions, mapper,
                mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class), schemeMapper,
                labelResolver);
        var service = new InspectionExecutionService(taskMapper, mock(ConversationMapper.class), messages, observations,
                mapper, executionMapper, taskItemMapper, scope,
                llm, mock(UsageCounterRecorder.class), results,
                mock(io.github.opensabre.iqc.label.LabelCandidateService.class), dependencies);
        return new TrialHarness(task, service, llm, release, tasks, labelResolver);
    }

    private record TrialHarness(InspectionTask task, InspectionExecutionService service,
                                io.github.opensabre.iqc.result.llm.LlmQualityProvider llm,
                                io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release,
                                InspectionTaskService tasks, LabelResolutionService labelResolver) { }

    private record RouteBatch(TrialHarness harness, InspectionTask task, JsonNode snapshot,
            SchemeDependencyResolver.RoutePlan plan, ItemRouteRunner.Run run,
            io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation business,
            List<ConversationMessage> context, List<TaskItem> items, List<InspectionResult> projections) { }

    /** Fixture-only protocol replacement preserves production route creation/start gates while testing real persistence. */
    private RouteBatch newIsolatedRouteBatch() throws Exception {
        return newIsolatedRouteBatch(null);
    }

    private RouteBatch newIsolatedRouteBatch(TrialApplicability applicability) throws Exception {
        var harness = newDraftTrial(true, false, false, true, applicability, false);
        var queued = harness.service().queue(harness.task().getId());
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item("house-check", "住房核查", new SchemeDefinition.RuleReference("rule-1", 1),
                        SchemeDefinition.HitMeaning.VIOLATION, applicability == null ? null : applicability.rule(), null,
                        new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null))),
                null, harness.release().definition().scoring());
        var resolver = (SchemeDependencyResolver) org.springframework.test.util.ReflectionTestUtils.getField(harness.service(), "schemeDependencies");
        var dependencies = resolver.previewRoutes(definition);
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot("路线事务验证", "route", null,
                "测试", definition.forTaskSnapshot(), dependencies);
        var snapshot = mapper.createObjectNode(); snapshot.set("rules", mapper.valueToTree(dependencies.rules()));
        snapshot.putObject("executionStrategy").put("schemaVersion", "1.0").put("mode", "RULE_ONLY");
        var marker = snapshot.putObject("schemeSnapshot").put("kind", "DRAFT_TRIAL").put("schemeId", "scheme-1");
        marker.set("release", mapper.valueToTree(release));
        marker.put("contentHash", io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(marker.path("release").toString()));
        queued.setStatus("RUNNING"); queued.setRuleSnapshotJson(snapshot.toString()); queued.setLabelScopeSnapshotJson(null);
        queued.setAgentSnapshotJson(null); taskMapper.updateById(queued);
        // MyBatis omits nullable updates by default; this fixture explicitly clears the preceding joint-label snapshot.
        jdbc.update("UPDATE iqc_inspection_task SET label_scope_snapshot_json = NULL WHERE id = ?", queued.getId());
        queued = taskMapper.selectById(queued.getId());
        var context = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, "conversation-1").orderByAsc(ConversationMessage::getSequenceNo));
        var items = taskItemMapper.selectList(Wrappers.<TaskItem>lambdaQuery().eq(TaskItem::getExecutionId, queued.getCurrentExecutionId())
                .orderByAsc(TaskItem::getSequenceNo));
        var run = harness.service().evaluateItemRoutes(queued, dependencies.itemExecutionPlan(), dependencies.rules(), context);
        var business = harness.service().scoreItemRoutes(release.definition(), dependencies.itemExecutionPlan(), run, context, dependencies.rules());
        var projections = context.stream().map(message -> {
            var observation = new InspectionResult(); observation.setTaskId(harness.task().getId());
            observation.setExecutionId(items.getFirst().getExecutionId()); observation.setConversationId(message.getConversationId());
            observation.setMessageId(message.getId()); observation.setSpeakerRole(message.getSpeakerRole());
            observation.setResultStatus("HIT"); observation.setRiskLevel("LOW"); observation.setDeduction(0);
            observation.setReason("事务回滚夹具观察"); observation.setEvidenceJson("[]"); observation.setFindingJson("[]");
            observation.setRuleBreakdownJson("[]"); observation.setSuggestionJson("[]"); return observation;
        }).toList();
        return new RouteBatch(harness, queued, snapshot, dependencies.itemExecutionPlan(), run, business, context, items, projections);
    }

    @Test
    void routeProcessCommitsActualResultsAndTaskItemsTogetherAndReplayIsIdempotent() throws Exception {
        var batch = newIsolatedRouteBatch();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(batch.harness().service(), "processConversation",
                batch.task(), batch.task().getCurrentExecutionId(), batch.snapshot(), batch.items());
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_result")).isEqualTo(2);
        assertThat(count("iqc_inspection_evidence")).isEqualTo(2);
        assertThat(count("iqc_inspection_label_result")).isZero();
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result", java.math.BigDecimal.class))
                .isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE status='SUCCEEDED' AND result_id IS NOT NULL AND attempt_count=1", Integer.class))
                .isEqualTo(2);
        var payload = mapper.readTree(jdbc.queryForObject("SELECT finding_json FROM iqc_inspection_rule_result", String.class));
        assertThat(payload.path("schemaVersion").asText()).isEqualTo("iqc-rule-contexts-v1");
        assertThat(payload.path("contexts")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_result WHERE score IS NULL AND deduction=0", Integer.class)).isEqualTo(2);
        var completed = taskItemMapper.selectList(Wrappers.<TaskItem>lambdaQuery().eq(TaskItem::getExecutionId, batch.task().getCurrentExecutionId()));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(batch.harness().service(), "processConversation",
                batch.task(), batch.task().getCurrentExecutionId(), batch.snapshot(), completed);
        assertThat(count("iqc_inspection_result")).isEqualTo(2);
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
        verify(batch.harness().llm(), org.mockito.Mockito.never()).evaluate(anyString(), any(JsonNode.class), anyString());
    }

    @Test
    void routedReleaseQueuesAndRunsThroughTheNormalTaskServices() throws Exception {
        var fixture = newIsolatedRouteBatch();
        var release = mapper.treeToValue(fixture.snapshot().at("/schemeSnapshot/release"),
                io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        var trial = fixture.harness().tasks().createSchemeTrial("scheme-1", 1, "逐项路线", List.of("conversation-1"), release);
        assertThat(trial.getStatus()).isEqualTo("CREATED");
        var queued = fixture.harness().service().queue(trial.getId());
        var completed = fixture.harness().service().run(queued.getId(), queued.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED"); assertThat(completed.getProcessedMessages()).isEqualTo(2);
        assertThat(completed.getFailedMessages()).isZero();
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                java.math.BigDecimal.class, trial.getId())).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED' AND result_id IS NOT NULL",
                Integer.class, queued.getCurrentExecutionId())).isEqualTo(2);
        assertThatCode(release.definition()::requireExecutable).doesNotThrowAnyException();
    }

    @Test
    void publishedTemplateCreatesAndRunsAFrozenPerItemRoute() throws Exception {
        var fixture = newIsolatedRouteBatch();
        var trialRelease = mapper.treeToValue(fixture.snapshot().at("/schemeSnapshot/release"),
                io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        var trialDefinition = trialRelease.definition();
        var authored = new SchemeDefinition(SchemeDefinition.SCHEMA, trialDefinition.items(), trialDefinition.agent(),
                trialDefinition.scoring(), trialDefinition.runLimits(), trialDefinition.labels());
        var storedRelease = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                trialRelease.name(), trialRelease.code(), trialRelease.description(), trialRelease.businessScene(),
                authored, trialRelease.dependencies());
        String snapshotJson = mapper.writeValueAsString(storedRelease);
        var version = new io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion();
        version.setSchemeId("scheme-1"); version.setVersionNo(1); version.setSnapshotJson(snapshotJson);
        version.setContentHash(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(snapshotJson));

        var created = fixture.harness().tasks().createFromScheme("已发布路线模板", List.of("conversation-1"), 1, version);
        var schemeSnapshot = mapper.readTree(created.getRuleSnapshotJson()).path("schemeSnapshot");
        assertThat(schemeSnapshot.path("kind").asText()).isEqualTo("PUBLISHED");
        assertThat(schemeSnapshot.path("release").path("definition").path("schemaVersion").asText())
                .isEqualTo(SchemeDefinition.ROUTED_TASK_SCHEMA);
        assertThat(schemeSnapshot.path("publishedContentHash").asText()).isEqualTo(version.getContentHash());

        var queued = fixture.harness().service().queue(created.getId());
        var completed = fixture.harness().service().run(queued.getId(), queued.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                java.math.BigDecimal.class, created.getId())).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED'",
                Integer.class, queued.getCurrentExecutionId())).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "没有房子,SUCCEEDED,FINAL,90,HIT,false", "贷款咨询,SUCCEEDED,NOT_APPLICABLE,,NOT_HIT,false",
            "没有房子,SUCCEEDED,FINAL,90,HIT,true", "贷款咨询,SUCCEEDED,NOT_APPLICABLE,,NOT_HIT,true"})
    void conditionalRouteExpertTrialRunsOrSkipsThroughNormalServicesAndPersistsTheGate(
            String conditionExpression, String taskStatus, String scoreStatus, String finalScore, String gateStatus,
            boolean useAllowedVariant) throws Exception {
        var applicability = new TrialApplicability(new SchemeDefinition.RuleReference("condition-rule", 1), conditionExpression);
        var fixture = newIsolatedRouteBatch(applicability);
        var release = mapper.treeToValue(fixture.snapshot().at("/schemeSnapshot/release"),
                io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        String selectedVariant = null;
        if (useAllowedVariant) {
            var original = release.definition();
            var item = original.items().getFirst();
            var route = item.execution();
            var variants = new io.github.opensabre.iqc.scheme.SchemeExecutionVariants("conditional-route", List.of(
                    new io.github.opensabre.iqc.scheme.SchemeExecutionVariants.Variant("conditional-route", "条件路线",
                            "覆盖全部质检项", "本地规则", Map.of(item.itemCode(), route))));
            var baseItem = new SchemeDefinition.Item(item.itemCode(), item.name(), item.rule(), item.hitMeaning(),
                    item.appliesWhen(), item.inputScope(), null);
            var base = new SchemeDefinition(original.schemaVersion(), List.of(baseItem), original.agent(), original.scoring(),
                    original.runLimits(), original.labels(), variants);
            var expanded = variants.expand(base, "conditional-route");
            var selectedDefinition = new SchemeDefinition(expanded.schemaVersion(), expanded.items(), expanded.agent(),
                    expanded.scoring(), expanded.runLimits(), expanded.labels(), variants);
            release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                    release.name(), release.code(), release.description(), release.businessScene(), selectedDefinition,
                    release.dependencies(), "conditional-route");
            selectedVariant = "conditional-route";
        }
        var trial = fixture.harness().tasks().createSchemeTrial(
                "scheme-1", 1, "条件逐项路线", List.of("conversation-1"), release, null, null, selectedVariant);
        var queued = fixture.harness().service().queue(trial.getId());
        var completed = fixture.harness().service().run(queued.getId(), queued.getCurrentExecutionId());

        assertThat(completed.getStatus()).isEqualTo(taskStatus);
        assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result WHERE task_id=?",
                String.class, trial.getId())).isEqualTo(scoreStatus);
        if (finalScore == null || finalScore.isBlank()) {
            assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                    java.math.BigDecimal.class, trial.getId())).isNull();
        } else {
            assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                    java.math.BigDecimal.class, trial.getId())).isEqualByComparingTo(finalScore);
        }
        var conversationResultId = jdbc.queryForObject("SELECT id FROM iqc_inspection_conversation_result WHERE task_id=?",
                String.class, trial.getId());
        var routeResult = persistedRuleResults.selectOne(Wrappers.<io.github.opensabre.iqc.result.model.RuleInspectionResult>lambdaQuery()
                .eq(io.github.opensabre.iqc.result.model.RuleInspectionResult::getConversationResultId,
                        conversationResultId)
                .eq(io.github.opensabre.iqc.result.model.RuleInspectionResult::getRuleId, "rule-1"));
        var routeContexts = mapper.readTree(routeResult.getFindingJson()).path("contexts");
        var finalContext = java.util.stream.StreamSupport.stream(routeContexts.spliterator(), false)
                .filter(context -> "DIRECT".equals(context.path("phase").asText())).findFirst().orElseThrow();
        if ("NOT_APPLICABLE".equals(scoreStatus)) {
            assertThat(finalContext.path("status").asText()).isEqualTo("NOT_EVALUATED");
        } else {
            assertThat(finalContext.path("status").asText()).isEqualTo("HIT");
        }
        var conditionResult = persistedRuleResults.selectOne(Wrappers.<io.github.opensabre.iqc.result.model.RuleInspectionResult>lambdaQuery()
                .eq(io.github.opensabre.iqc.result.model.RuleInspectionResult::getConversationResultId, conversationResultId)
                .eq(io.github.opensabre.iqc.result.model.RuleInspectionResult::getRuleId, "condition-rule"));
        var conditionContexts = mapper.readTree(conditionResult.getFindingJson()).path("contexts");
        var gateContext = java.util.stream.StreamSupport.stream(conditionContexts.spliterator(), false)
                .filter(context -> "APPLICABILITY".equals(context.path("phase").asText())).findFirst().orElseThrow();
        assertThat(gateContext.path("status").asText()).isEqualTo(gateStatus);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"local", "assisted"})
    void mixedLocalAndLlmAlternativesShareTemplateCapabilityButOnlySelectedRouteUsesTheModel(String code) throws Exception {
        var harness = newDraftTrial(true, true, false, true, null, true);
        var direct = new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null);
        var assisted = new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_THEN_RULE, null,
                new SchemeDefinition.RuleReference("rule-2", 1), SchemeDefinition.InputScope.CONVERSATION, null);
        var variants = new io.github.opensabre.iqc.scheme.SchemeExecutionVariants("local", List.of(
                new io.github.opensabre.iqc.scheme.SchemeExecutionVariants.Variant("local", "本地核查", "完整住房核查", "不调用模型", Map.of("house-check", direct)),
                new io.github.opensabre.iqc.scheme.SchemeExecutionVariants.Variant("assisted", "候选辅助核查", "完整住房核查", "调用模型一次", Map.of("house-check", assisted))));
        var authored = new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(new SchemeDefinition.Item("house-check", "住房核查",
                new SchemeDefinition.RuleReference("rule-1", 1), SchemeDefinition.HitMeaning.VIOLATION)),
                new SchemeDefinition.AgentReference("agent-1", 1), harness.release().definition().scoring(), null, null, variants);
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_config_json=? WHERE id='scheme-1'", mapper.writeValueAsString(authored));
        var resolver = (SchemeDependencyResolver) org.springframework.test.util.ReflectionTestUtils.getField(harness.service(), "schemeDependencies");
        var scope = (io.github.opensabre.iqc.shared.IqcDataScope) org.springframework.test.util.ReflectionTestUtils.getField(harness.service(), "dataScope");
        var schemes = new io.github.opensabre.iqc.scheme.InspectionSchemeService(schemeMapper, schemeVersionMapper, resolver, scope, mapper);
        var recommendation = schemes.preview("scheme-1", 1);
        assertThat(recommendation.selectedVariantCode()).isEqualTo("local");
        assertThat(recommendation.dependencies().agent()).isNull();
        assertThat(recommendation.variantDependencies().get("assisted").agent()).isNotNull();
        var selected = recommendation.selectVariant(code);
        var candidate = mapper.createObjectNode().put("schemaVersion", "iqc-item-candidates-v1").put("outcome", "MATCH");
        candidate.putArray("quotes").addObject().put("messageId", "message-1").put("conversationId", "conversation-1")
                .put("speakerRole", "customer").put("text", "没有房子");
        when(harness.llm().evaluateCandidates(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenReturn(new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(true, true, "候选", candidate.toString()));
        var trial = harness.tasks().createSchemeTrial("scheme-1", 1, "允许变体", List.of("conversation-1"), selected);
        var snapshot = mapper.readTree(trial.getRuleSnapshotJson());
        assertThat(snapshot.at("/schemeSnapshot/selectedVariantCode").asText()).isEqualTo(code);
        assertThat(snapshot.at("/schemeSnapshot/release/definition/agent/id").asText()).isEqualTo("agent-1");
        assertThat(snapshot.at("/schemeSnapshot/release/variantDependencies")).hasSize(2);
        assertThat(trial.getAgentSnapshotJson()).isEqualTo("local".equals(code) ? null : selected.dependencies().agent().toString());
        assertThat(snapshot.at("/executionStrategy/mode").asText()).isEqualTo("local".equals(code) ? "RULE_ONLY" : "INDEPENDENT");
        var queued = harness.service().queue(trial.getId());
        var completed = harness.service().run(trial.getId(), queued.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertTrialItem(queued.getCurrentExecutionId(), "FAIL", "FINAL", "90");
        if ("local".equals(code)) org.mockito.Mockito.verifyNoInteractions(harness.llm());
        else {
            verify(harness.llm(), org.mockito.Mockito.times(1)).evaluateCandidates(anyList(), any(JsonNode.class), any(JsonNode.class), anyString());
            org.mockito.Mockito.verifyNoMoreInteractions(harness.llm());
        }
        assertThatCode(selected.definition()::requireExecutable).doesNotThrowAnyException();
        var agents = (io.github.opensabre.iqc.agent.dao.QualityAgentMapper) org.springframework.test.util.ReflectionTestUtils.getField(resolver, "agents");
        when(agents.selectById("agent-1")).thenReturn(null);
        int existingTasks = count("iqc_inspection_task");
        assertThatThrownBy(() -> schemes.preview("scheme-1", 1)).hasMessageContaining("智能体不存在或已停用");
        assertThat(count("iqc_inspection_task")).isEqualTo(existingTasks);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"没有房子,FAIL,90", "我,PASS,100", "虚构承诺,REVIEW_REQUIRED,PENDING"})
    void candidateRouteRunsThroughNormalTrialServicesAndCommitsOnlyTerminalBusinessScore(
            String quote, String itemStatus, String score) throws Exception {
        // Reuse published local/LLM fixtures; the tested route task is created normally without snapshot replacement.
        var harness = newDraftTrial(true, true, false, true, null, true);
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item("house-check", "住房核查",
                        new SchemeDefinition.RuleReference("rule-1", 1), SchemeDefinition.HitMeaning.VIOLATION,
                        null, null, new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_THEN_RULE, null,
                        new SchemeDefinition.RuleReference("rule-2", 1), SchemeDefinition.InputScope.CONVERSATION, null))),
                new SchemeDefinition.AgentReference("agent-1", 1), harness.release().definition().scoring());
        var resolver = (SchemeDependencyResolver) org.springframework.test.util.ReflectionTestUtils.getField(harness.service(), "schemeDependencies");
        var dependencies = resolver.previewRoutes(definition);
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                "候选路线试跑", "candidate", null, "销售", definition, dependencies);
        var candidate = mapper.createObjectNode().put("schemaVersion", "iqc-item-candidates-v1").put("outcome", "MATCH");
        candidate.putArray("quotes").addObject().put("messageId", "message-1").put("conversationId", "conversation-1")
                .put("speakerRole", "customer").put("text", quote);
        when(harness.llm().evaluateCandidates(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenReturn(new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(true, true, "候选", candidate.toString()));
        var trial = harness.tasks().createSchemeTrial("scheme-1", 1, "候选路线试跑", List.of("conversation-1"), release);
        var queued = harness.service().queue(trial.getId());
        var completed = harness.service().run(trial.getId(), queued.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getProcessedMessages()).isEqualTo(2); assertThat(completed.getFailedMessages()).isZero();
        assertTrialItem(queued.getCurrentExecutionId(), itemStatus, "PENDING".equals(score) ? "PENDING" : "FINAL",
                "PENDING".equals(score) ? null : score);
        assertThat(count("iqc_inspection_label_result")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED' AND result_id IS NOT NULL",
                Integer.class, queued.getCurrentExecutionId())).isEqualTo(2);
        verify(harness.llm(), org.mockito.Mockito.times(1)).evaluateCandidates(anyList(), any(JsonNode.class), any(JsonNode.class), anyString());
        verify(harness.llm(), org.mockito.Mockito.never()).evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "LLM_ONLY,true,true,FAIL,90", "LLM_ONLY,true,false,PASS,100",
            "RULE_THEN_LLM,false,true,PASS,100", "RULE_THEN_LLM,true,false,PASS,100",
            "RULE_THEN_LLM,true,true,FAIL,90"})
    void directAndPrefilterLlmRoutesUseTerminalVerdictsInNormalTaskTransactions(
            String routeName, boolean localHit, boolean modelHit, String itemStatus, String score) throws Exception {
        var harness = newDraftTrial(true, true, false, true, null, true);
        if (!localHit) jdbc.update("UPDATE iqc_conversation_message SET content='我考虑一下' WHERE conversation_id='conversation-1'");
        var route = SchemeDefinition.Route.valueOf(routeName);
        var execution = new SchemeDefinition.Execution(route,
                route == SchemeDefinition.Route.RULE_THEN_LLM ? new SchemeDefinition.RuleReference("rule-1", 1) : null,
                null, null, route == SchemeDefinition.Route.RULE_THEN_LLM ? true : null);
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item("house-check", "住房核查",
                        new SchemeDefinition.RuleReference("rule-2", 1), SchemeDefinition.HitMeaning.VIOLATION,
                        null, SchemeDefinition.InputScope.CONVERSATION, execution)),
                new SchemeDefinition.AgentReference("agent-1", 1), harness.release().definition().scoring());
        var resolver = (SchemeDependencyResolver) org.springframework.test.util.ReflectionTestUtils.getField(harness.service(), "schemeDependencies");
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                "LLM 终态试跑", "terminal", null, "销售", definition, resolver.previewRoutes(definition));
        var output = new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(true, modelHit, "受控终态");
        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString())).thenReturn(output);
        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), any(JsonNode.class), anyString())).thenReturn(output);
        var trial = harness.tasks().createSchemeTrial("scheme-1", 1, "LLM 终态试跑", List.of("conversation-1"), release);
        var queued = harness.service().queue(trial.getId());
        var completed = harness.service().run(trial.getId(), queued.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getProcessedMessages()).isEqualTo(2); assertThat(completed.getFailedMessages()).isZero();
        assertTrialItem(queued.getCurrentExecutionId(), itemStatus, "FINAL", score);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED' AND result_id IS NOT NULL",
                Integer.class, queued.getCurrentExecutionId())).isEqualTo(2);
        verify(harness.llm(), org.mockito.Mockito.times(route == SchemeDefinition.Route.LLM_ONLY ? 1 : 0))
                .evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString());
        verify(harness.llm(), org.mockito.Mockito.times(route == SchemeDefinition.Route.RULE_THEN_LLM && localHit ? 1 : 0))
                .evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), any(JsonNode.class), anyString());
        assertThat(count("iqc_inspection_label_result")).isZero();
    }

    @Test
    void routeBatchFailureRollsBackConversationEvidenceObservationAndFirstProgressUpdate() throws Exception {
        var batch = newIsolatedRouteBatch();
        jdbc.update("UPDATE iqc_task_item SET status='CANCELLED' WHERE id=?", batch.items().get(1).getId());
        assertThatThrownBy(() -> results.materializeItemRouteBatch(batch.task(), batch.task().getCurrentExecutionId(),
                batch.snapshot(), batch.context(), batch.plan(), batch.run(), batch.business(), batch.projections(), batch.items()))
                .hasMessageContaining("处理进度已失效");
        for (String table : List.of("iqc_inspection_conversation_result", "iqc_inspection_rule_result", "iqc_inspection_evidence", "iqc_inspection_result"))
            assertThat(count(table)).as(table).isZero();
        var first = taskItemMapper.selectById(batch.items().getFirst().getId());
        assertThat(first.getStatus()).isEqualTo("PENDING"); assertThat(first.getResultId()).isNull(); assertThat(first.getAttemptCount()).isZero();
        jdbc.update("UPDATE iqc_task_item SET status='PENDING' WHERE id=?", batch.items().get(1).getId());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(batch.harness().service(), "processConversation",
                batch.task(), batch.task().getCurrentExecutionId(), batch.snapshot(), batch.items());
        assertThat(count("iqc_inspection_result")).isEqualTo(2);
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
    }

    @Test
    void routedLlmFailureRetriesWithNewExecutionAndPreservesOldPendingBusinessResults() throws Exception {
        var harness = newDraftTrial(true, true, false, true, null, true);
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item("house-check", "住房核查",
                        new SchemeDefinition.RuleReference("rule-2", 1), SchemeDefinition.HitMeaning.VIOLATION,
                        null, SchemeDefinition.InputScope.CONVERSATION,
                        new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_ONLY, null, null, null, null))),
                new SchemeDefinition.AgentReference("agent-1", 1), harness.release().definition().scoring());
        var resolver = (SchemeDependencyResolver) org.springframework.test.util.ReflectionTestUtils.getField(harness.service(), "schemeDependencies");
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                "路线重试", "retry", null, "销售", definition, resolver.previewRoutes(definition));
        var trial = harness.tasks().createSchemeTrial("scheme-1", 1, "路线重试", List.of("conversation-1"), release);
        var first = harness.service().queue(trial.getId());
        var failed = harness.service().run(trial.getId(), first.getCurrentExecutionId());
        assertThat(failed.getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(failed.getProcessedMessages()).isEqualTo(2); assertThat(failed.getFailedMessages()).isEqualTo(2);
        assertTrialItem(first.getCurrentExecutionId(), "ERROR", "PENDING", null);
        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenReturn(new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(true, false, "恢复后不违规"));
        var retry = harness.service().queue(trial.getId());
        assertThat(retry.getCurrentExecutionId()).isNotEqualTo(first.getCurrentExecutionId());
        var completed = harness.service().run(trial.getId(), retry.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getProcessedMessages()).isEqualTo(2); assertThat(completed.getFailedMessages()).isZero();
        assertTrialItem(first.getCurrentExecutionId(), "ERROR", "PENDING", null);
        assertTrialItem(retry.getCurrentExecutionId(), "PASS", "FINAL", "100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_conversation_result WHERE task_id=?", Integer.class, trial.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='FAILED' AND result_id IS NOT NULL",
                Integer.class, first.getCurrentExecutionId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED' AND result_id IS NOT NULL",
                Integer.class, retry.getCurrentExecutionId())).isEqualTo(2);
        verify(harness.llm(), org.mockito.Mockito.times(2)).evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString());
    }

    @Test
    void signedHttpRoutedTrialFreezesPlanDeduplicatesRequestAndPublishesReviewedRoute() throws Exception {
        var fixture = newIsolatedRouteBatch();
        createIsolatedSchemeVersionTable();
        var frozenDefinition = mapper.treeToValue(fixture.snapshot().at("/schemeSnapshot/release/definition"), SchemeDefinition.class);
        var draft = new SchemeDefinition(SchemeDefinition.SCHEMA, frozenDefinition.items(), frozenDefinition.agent(),
                frozenDefinition.scoring(), frozenDefinition.runLimits());
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_config_json=?,created_by='alice',owner_group_id='g1' WHERE id='scheme-1'",
                mapper.writeValueAsString(draft));
        jdbc.update("UPDATE iqc_conversation SET created_by='alice',owner_group_id='g1' WHERE id='conversation-1'");
        when(httpOrganization.getUserByUniqueId(anyString())).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("eve", "Eve", "eve", "g2", "Group 2")));
        when(httpOrganization.getUserByUniqueId("alice")).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("alice", "Alice", "alice", "g1", "Group 1")));
        when(httpRateLimiter.check(any())).thenReturn(new RateLimitDecision(true, 60, 0, 60, null, "isolated-test"));
        String alice = signedHttpIdentity("alice"), path = "/api/iqc/schemes/scheme-1/trials";
        String request = "{\"expectedRevision\":1,\"conversationIds\":[\"conversation-1\"],\"requestId\":\"route_http_trial_01\"}";
        int baselineTasks = count("iqc_inspection_task");
        assertThat(http(null, path, request).statusCode()).isEqualTo(401);
        assertHttpBusinessDenied(http(signedHttpIdentity("eve"), path, request));
        assertThat(count("iqc_inspection_task")).isEqualTo(baselineTasks);
        var created = httpData(http(alice, path, request));
        String taskId = created.path("id").asText();
        var snapshot = mapper.readTree(created.path("ruleSnapshotJson").asText());
        assertThat(snapshot.at("/schemeSnapshot/release/definition/schemaVersion").asText()).isEqualTo(SchemeDefinition.ROUTED_TASK_SCHEMA);
        assertThat(snapshot.at("/schemeSnapshot/release/dependencies/itemExecutionPlan/items/0/route").asText()).isEqualTo("RULE_ONLY");
        assertThat(snapshot.at("/schemeSnapshot/contentHash").asText()).isEqualTo(
                io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(snapshot.at("/schemeSnapshot/release").toString()));
        assertThat(httpData(http(alice, path, request)).path("id").asText()).isEqualTo(taskId);
        assertThat(count("iqc_inspection_task")).isEqualTo(baselineTasks + 1);
        httpData(http(alice, "/api/iqc/tasks/" + taskId + "/run", "{}"));
        awaitHttpTask(alice, taskId);
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                java.math.BigDecimal.class, taskId)).isEqualByComparingTo("90");
        var publication = httpData(http(alice, "/api/iqc/schemes/scheme-1/publish",
                "{\"expectedRevision\":1,\"trialTaskId\":\"" + taskId + "\",\"resultsReviewed\":true}"));
        assertThat(publication.path("versionNo").asInt()).isEqualTo(1);
        assertThat(schemeMapper.selectById("scheme-1").getActivePublishedVersion()).isEqualTo(1);
        var release = mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM iqc_inspection_scheme_version WHERE scheme_id=?",
                String.class, "scheme-1"));
        assertThat(release.at("/definition/schemaVersion").asText()).isEqualTo(SchemeDefinition.ROUTED_TASK_SCHEMA);
        assertThat(release.at("/dependencies/itemExecutionPlan/items/0/route").asText()).isEqualTo("RULE_ONLY");
    }

    @Test
    void signedHttpApprovedVariantsFreezeSelectionRejectChangesAndSurviveDraftRevision() throws Exception {
        var fixture = newIsolatedRouteBatch();
        var original = mapper.treeToValue(fixture.snapshot().at("/schemeSnapshot/release/definition"), SchemeDefinition.class);
        var routes = Map.of("house-check", original.items().getFirst().execution());
        var variants = new io.github.opensabre.iqc.scheme.SchemeExecutionVariants("standard", List.of(
                new io.github.opensabre.iqc.scheme.SchemeExecutionVariants.Variant("standard", "标准检查", "完整住房检查", "本地规则", routes),
                new io.github.opensabre.iqc.scheme.SchemeExecutionVariants.Variant("alternate", "另一批准方案", "完整住房检查", "本地规则", routes)));
        var draft = new SchemeDefinition(SchemeDefinition.SCHEMA, original.items(), original.agent(), original.scoring(),
                original.runLimits(), original.labels(), variants);
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_config_json=?,created_by='alice',owner_group_id='g1' WHERE id='scheme-1'",
                mapper.writeValueAsString(draft));
        jdbc.update("UPDATE iqc_conversation SET created_by='alice',owner_group_id='g1' WHERE id='conversation-1'");
        when(httpOrganization.getUserByUniqueId("alice")).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("alice", "Alice", "alice", "g1", "Group 1")));
        when(httpRateLimiter.check(any())).thenReturn(new RateLimitDecision(true, 60, 0, 60, null, "isolated-test"));
        String token = signedHttpIdentity("alice"), path = "/api/iqc/schemes/scheme-1/trials";
        String request = "{\"expectedRevision\":1,\"conversationIds\":[\"conversation-1\"],\"requestId\":\"variant_http_trial_01\"}";
        int baseline = count("iqc_inspection_task");
        var invalid = mapper.readTree(http(token, path, request.replace("}", ",\"variantCode\":\"unknown\"}")).body());
        assertThat(invalid.path("code").asText()).isEqualTo("IQC-1003");
        assertThat(count("iqc_inspection_task")).isEqualTo(baseline);
        var created = httpData(http(token, path, request));
        String taskId = created.path("id").asText();
        var frozen = mapper.readTree(created.path("ruleSnapshotJson").asText());
        assertThat(frozen.at("/schemeSnapshot/selectedVariantCode").asText()).isEqualTo("standard");
        assertThat(frozen.at("/schemeSnapshot/release/selectedVariantCode").asText()).isEqualTo("standard");
        assertThat(frozen.at("/schemeSnapshot/release/definition/executionVariants/variants")).hasSize(2);
        assertThat(frozen.at("/schemeSnapshot/release/variantDependencies")).hasSize(2);
        assertThat(frozen.at("/schemeSnapshot/release/variantDependencies/standard"))
                .isEqualTo(frozen.at("/schemeSnapshot/release/dependencies"));
        assertThat(frozen.at("/schemeSnapshot/contentHash").asText()).isEqualTo(
                io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(frozen.at("/schemeSnapshot/release").toString()));
        // An isolated unsupported release fixture must not turn the new task selection API into a gate bypass.
        createIsolatedSchemeVersionTable();
        var gatedVersion = new io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion();
        gatedVersion.setId("gated-variant-release"); gatedVersion.setSchemeId("scheme-1"); gatedVersion.setVersionNo(1);
        gatedVersion.setSourceDraftRevision(1);
        gatedVersion.setSnapshotJson(frozen.at("/schemeSnapshot/release").toString());
        gatedVersion.setContentHash(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(gatedVersion.getSnapshotJson()));
        schemeVersionMapper.insert(gatedVersion);
        String publishedPath = "/api/iqc/schemes/scheme-1/versions/1/tasks";
        for (String code : List.of("standard", "alternate", "unknown")) {
            var blocked = mapper.readTree(http(token, publishedPath,
                    "{\"requestId\":\"gated-variant-task-01\",\"name\":\"正式路线\",\"conversationIds\":[\"conversation-1\"],\"variantCode\":\"" + code + "\"}").body());
            assertThat(blocked.path("code").asText()).isEqualTo(code.equals("unknown") ? "IQC-1003" : "IQC-1006");
            assertThat(count("iqc_inspection_task")).isEqualTo(baseline + 1);
        }
        var blockedSchedule = mapper.readTree(http(token, publishedPath,
                "{\"requestId\":\"gated-variant-schedule-01\",\"taskType\":\"SCHEDULED\",\"scheduledTime\":\"2030-01-01T10:00:00\",\"variantCode\":\"alternate\"}").body());
        assertThat(blockedSchedule.path("code").asText()).isEqualTo("IQC-1006");
        assertThat(count("iqc_inspection_task")).isEqualTo(baseline + 1);
        String explicitDefault = request.replace("}", ",\"variantCode\":\"standard\"}");
        assertThat(httpData(http(token, path, explicitDefault)).path("id").asText()).isEqualTo(taskId);
        var changed = mapper.readTree(http(token, path, request.replace("}", ",\"variantCode\":\"alternate\"}")).body());
        assertThat(changed.path("code").asText()).isEqualTo("IQC-1003");
        assertThat(count("iqc_inspection_task")).isEqualTo(baseline + 1);

        var missingDependency = (com.fasterxml.jackson.databind.node.ObjectNode) frozen.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingDependency.at("/schemeSnapshot/release/variantDependencies"))
                .remove("alternate");
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingDependency.path("schemeSnapshot")).put("contentHash",
                io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(missingDependency.at("/schemeSnapshot/release").toString()));
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(missingDependency, null, mapper))
                .hasMessageContaining("变体依赖快照");

        var corruptAlternative = (com.fasterxml.jackson.databind.node.ObjectNode) frozen.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) corruptAlternative.at("/schemeSnapshot/release/variantDependencies/alternate/itemExecutionPlan"))
                .put("schemaVersion", "unsupported-plan");
        ((com.fasterxml.jackson.databind.node.ObjectNode) corruptAlternative.path("schemeSnapshot")).put("contentHash",
                io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(corruptAlternative.at("/schemeSnapshot/release").toString()));
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(corruptAlternative, null, mapper))
                .hasMessageContaining("允许变体的冻结检测依赖或执行计划无效");

        var tampered = (com.fasterxml.jackson.databind.node.ObjectNode) frozen.deepCopy();
        var selectedTampered = tampered;
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("schemeSnapshot")).put("selectedVariantCode", "alternate");
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(selectedTampered, null, mapper))
                .hasMessageContaining("冻结方案摘要");
        tampered = (com.fasterxml.jackson.databind.node.ObjectNode) frozen.deepCopy();
        var finalTampered = tampered;
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.at("/schemeSnapshot/release/definition/items/0/execution"))
                .put("route", "LLM_ONLY");
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("schemeSnapshot")).put("contentHash",
                io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(tampered.at("/schemeSnapshot/release").toString()));
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(finalTampered, null, mapper))
                .hasMessageContaining("所选允许策略变体");

        // Retry must use the original allowed recommendation even when the draft has been replaced.
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_revision=2,draft_config_json=? WHERE id='scheme-1'",
                mapper.writeValueAsString(new SchemeDefinition(SchemeDefinition.SCHEMA, original.items(), original.agent(), original.scoring())));
        assertThat(httpData(http(token, path, request)).path("id").asText()).isEqualTo(taskId);
        assertThat(taskMapper.selectById(taskId).getRuleSnapshotJson()).isEqualTo(created.path("ruleSnapshotJson").asText());
        String alternateRequest = request.replace("variant_http_trial_01", "variant_http_trial_02")
                .replace("}", ",\"variantCode\":\"alternate\"}");
        // Restore the authoring version only to test a different request's explicit approved selection.
        jdbc.update("UPDATE iqc_inspection_scheme SET draft_revision=1,draft_config_json=? WHERE id='scheme-1'", mapper.writeValueAsString(draft));
        var alternate = httpData(http(token, path, alternateRequest));
        assertThat(mapper.readTree(alternate.path("ruleSnapshotJson").asText()).at("/schemeSnapshot/selectedVariantCode").asText())
                .isEqualTo("alternate");
        assertThat(count("iqc_inspection_task")).isEqualTo(baseline + 2);
        httpData(http(token, "/api/iqc/tasks/" + taskId + "/run", "{}"));
        awaitHttpTask(token, taskId);
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                java.math.BigDecimal.class, taskId)).isEqualByComparingTo("90");
    }

    @Test
    void realTaskRowCancellationRejectsRouteCommitWithoutAnyPartialResult() throws Exception {
        var batch = newIsolatedRouteBatch();
        jdbc.update("UPDATE iqc_inspection_task SET status='CANCEL_REQUESTED' WHERE id=?", batch.task().getId());
        var result = results.materializeItemRouteBatch(batch.task(), batch.task().getCurrentExecutionId(), batch.snapshot(),
                batch.context(), batch.plan(), batch.run(), batch.business(), batch.projections(), batch.items());
        assertThat(result).isNull(); assertThat(count("iqc_inspection_conversation_result")).isZero();
        assertThat(count("iqc_inspection_result")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE status='PENDING' AND result_id IS NULL", Integer.class)).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CANCEL_REQUESTED", "PAUSE_REQUESTED"})
    void routeCommitWaitingOnTaskLockObservesConcurrentStateChange(String requestedStatus) throws Exception {
        var batch = newIsolatedRouteBatch();
        var enteredCommit = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.Wrapper<?> query = invocation.getArgument(0);
            if (query.getSqlSegment().contains("FOR UPDATE")) enteredCommit.countDown();
            return invocation.callRealMethod();
        }).when(taskMapper).selectOne(any());
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
            var pending = transaction.execute(status -> {
                // Hold the real task row while the result transaction reaches its own locking read.
                jdbc.queryForObject("SELECT id FROM iqc_inspection_task WHERE id=? FOR UPDATE", String.class, batch.task().getId());
                var future = worker.submit(() -> results.materializeItemRouteBatch(batch.task(), batch.task().getCurrentExecutionId(),
                        batch.snapshot(), batch.context(), batch.plan(), batch.run(), batch.business(), batch.projections(), batch.items()));
                try {
                    assertThat(enteredCommit.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
                }
                jdbc.update("UPDATE iqc_inspection_task SET status=? WHERE id=?", requestedStatus, batch.task().getId());
                return future;
            });
            assertThat(pending.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNull();
            for (String table : List.of("iqc_inspection_conversation_result", "iqc_inspection_rule_result", "iqc_inspection_evidence", "iqc_inspection_result"))
                assertThat(count(table)).as(table).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task WHERE id=?", String.class, batch.task().getId()))
                    .isEqualTo(requestedStatus);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE status='PENDING' AND result_id IS NULL AND attempt_count=0", Integer.class))
                    .isEqualTo(2);
        } finally {
            worker.shutdownNow(); assertThat(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void explicitConversationCheckCreatesFreezesRunsAndScoresOnceWithoutInventedCitations() throws Exception {
        var harness = newDraftTrial(true, true, false, true, null, true);
        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenReturn(new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(true, true, "完整会话命中"));
        var frozen = mapper.readTree(harness.task().getRuleSnapshotJson());
        assertThat(frozen.at("/schemeSnapshot/release/definition/schemaVersion").asText()).isEqualTo(SchemeDefinition.SCOPED_TASK_SCHEMA);
        assertThat(harness.task().getLabelScopeSnapshotJson()).isNull();
        var queued = harness.service().queue(harness.task().getId());
        var completed = harness.service().run(queued.getId(), queued.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        var context = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(harness.llm()).evaluateConversation(context.capture(), any(JsonNode.class), any(JsonNode.class), anyString());
        assertThat(context.getValue()).hasSize(2);
        verify(harness.llm(), org.mockito.Mockito.never()).evaluate(anyString(), any(JsonNode.class), any(JsonNode.class), anyString());
        assertThat(count("iqc_inspection_result")).isEqualTo(2);
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result", java.math.BigDecimal.class))
                .isEqualByComparingTo("90");
        var items = mapper.readTree(jdbc.queryForObject("SELECT business_item_results_json FROM iqc_inspection_conversation_result", String.class));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("matchedMessageIds")).isEmpty();
        assertThat(count("iqc_inspection_label_result")).isZero();
        assertThatCode(harness.release().definition()::requireExecutable).doesNotThrowAnyException();
    }

    private io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation knownFalseHouse(String messageId) {
        return new io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation(true, false,
                "客户明确否认", """
                {"hit":false,"reason":"客户明确否认","schemaVersion":"iqc-label-facts-v2","facts":[
                {"labelId":"house","valueCode":"owns","ruleId":"rule-2","subjectKind":"CURRENT_PARTICIPANT",
                "subjectRole":"customer","value":false,"evidence":[{"messageId":"%s","text":"没有房子"}]}]}
                """.formatted(messageId));
    }

    @Test
    void draftLabelOnlyTrialCreatesQueuesAndRunsToSuccessWithoutScore() throws Exception {
        var harness = newDraftTrial();
        var task = harness.task();
        var service = harness.service();
        var llm = harness.llm();

        assertThat(task.getStatus()).isEqualTo("CREATED");
        var queued = service.queue(task.getId());
        var executionId = queued.getCurrentExecutionId();
        assertThat(queued.getStatus()).isEqualTo("QUEUED");
        assertThat(count("iqc_task_item")).isEqualTo(1);
        assertThat(mapper.readTree(queued.getRuleSnapshotJson()).at("/schemeSnapshot/kind").asText())
                .isEqualTo("DRAFT_TRIAL");
        var completed = service.run(task.getId(), executionId);
        var retry = service.run(task.getId(), executionId);

        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(retry.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(taskMapper.selectById(task.getId()).getProcessedMessages()).isEqualTo(1);
        assertThat(count("iqc_task_execution")).isEqualTo(1);
        assertThat(executionMapper.selectById(executionId).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(executionMapper.selectById(executionId).getProcessedMessages()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_item", String.class)).isEqualTo("SUCCEEDED");
        assertThat(count("iqc_inspection_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score FROM iqc_inspection_result", Integer.class)).isNull();
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_evidence")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result", String.class))
                .isEqualTo("NOT_APPLICABLE");
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result", java.math.BigDecimal.class))
                .isNull();
        assertThat(count("iqc_inspection_label_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
        verifyNoInteractions(llm);
    }

    @Test
    void disabledRulePreventsDraftTrialFromEnteringQueue() throws Exception {
        var harness = newDraftTrial();
        jdbc.update("UPDATE iqc_quality_rule SET status = 'DISABLED' WHERE id = 'rule-1'");

        assertThatThrownBy(() -> harness.service().queue(harness.task().getId()))
                .hasMessageContaining("规则不存在或已停用");

        assertThat(taskMapper.selectById(harness.task().getId()).getStatus()).isEqualTo("CREATED");
        assertThat(count("iqc_task_execution")).isZero();
        assertThat(count("iqc_task_item")).isZero();
        assertThat(count("iqc_inspection_result")).isZero();
        verifyNoInteractions(harness.llm());
    }

    @Test
    void sharedDetectorProducesIndependentQualityScoreAndFalseLabelInOneTrial() throws Exception {
        var harness = newDraftTrial(true);
        var queued = harness.service().queue(harness.task().getId());

        var completed = harness.service().run(queued.getId(), queued.getCurrentExecutionId());

        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(count("iqc_inspection_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(1);
        assertThat(count("iqc_inspection_evidence")).isEqualTo(1);
        assertThat(count("iqc_inspection_label_result")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result", String.class))
                .isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result", java.math.BigDecimal.class))
                .isEqualByComparingTo("90");
        var items = mapper.readTree(jdbc.queryForObject(
                "SELECT business_item_results_json FROM iqc_inspection_conversation_result", String.class));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("status").asText()).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
        verifyNoInteractions(harness.llm());
    }

    @Test
    void publishedTemplateCreatesAndExecutesJointCheckAndLabelFromTheFrozenRelease() throws Exception {
        var harness = newDraftTrial(true);
        taskMapper.deleteById(harness.task().getId());
        String frozenRelease = mapper.writeValueAsString(harness.release());
        var version = new io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion();
        version.setSchemeId("scheme-1"); version.setVersionNo(1); version.setSnapshotJson(frozenRelease);
        version.setContentHash(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(frozenRelease));

        var created = harness.tasks().createFromScheme("已发布联合模板", List.of("conversation-1"), 1, version);
        var snapshot = mapper.readTree(created.getRuleSnapshotJson());
        assertThat(snapshot.at("/schemeSnapshot/kind").asText()).isEqualTo("PUBLISHED");
        assertThat(snapshot.at("/schemeSnapshot/release/definition/schemaVersion").asText())
                .isEqualTo(SchemeDefinition.JOINT_TASK_SCHEMA);
        assertThat(snapshot.at("/schemeSnapshot/publishedContentHash").asText()).isEqualTo(version.getContentHash());
        assertThat(created.getLabelScopeSnapshotJson()).contains("house", "owns");

        var queued = harness.service().queue(created.getId());
        var completed = harness.service().run(queued.getId(), queued.getCurrentExecutionId());
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result WHERE task_id=?",
                String.class, created.getId())).isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                java.math.BigDecimal.class, created.getId())).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result WHERE conversation_result_id=(SELECT id FROM iqc_inspection_conversation_result WHERE task_id=?)",
                String.class, created.getId())).contains("\"status\":\"KNOWN\"", "\"value\":false");
        verifyNoInteractions(harness.llm());
    }

    @Test
    void publishedRoutedTemplateMaterializesItsFrozenJointLabelsAndScoreTogether() throws Exception {
        var harness = newDraftTrial(true);
        taskMapper.deleteById(harness.task().getId());
        var base = harness.release();
        var item = base.definition().items().getFirst();
        var routed = new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item(item.itemCode(), item.name(), item.rule(), item.hitMeaning(),
                        item.appliesWhen(), item.inputScope(), new SchemeDefinition.Execution(
                        SchemeDefinition.Route.RULE_ONLY, null, null, null, null))),
                base.definition().agent(), base.definition().scoring(), base.definition().runLimits(), base.definition().labels());
        when(harness.labelResolver().resolveVersions(routed.labels())).thenReturn(base.dependencies().labels());
        var resolver = (SchemeDependencyResolver) org.springframework.test.util.ReflectionTestUtils.getField(
                harness.service(), "schemeDependencies");
        var dependencies = resolver.previewRoutes(routed);
        var planJson = mapper.valueToTree(dependencies.itemExecutionPlan());
        assertThat(planJson.path("labelContextKeys")).hasSize(1);
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                base.name(), base.code(), base.description(), base.businessScene(), routed.forTaskSnapshot(), dependencies);
        String frozenRelease = mapper.writeValueAsString(release);
        var version = new io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion();
        version.setSchemeId("scheme-1"); version.setVersionNo(1); version.setSnapshotJson(frozenRelease);
        version.setContentHash(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(frozenRelease));

        var created = harness.tasks().createFromScheme("已发布逐项路线联合模板", List.of("conversation-1"), 1, version);
        var queued = harness.service().queue(created.getId());
        var completed = harness.service().run(queued.getId(), queued.getCurrentExecutionId());

        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result WHERE task_id=?",
                String.class, created.getId())).isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE task_id=?",
                java.math.BigDecimal.class, created.getId())).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_label_result WHERE conversation_result_id="
                + "(SELECT id FROM iqc_inspection_conversation_result WHERE task_id=?)", Integer.class, created.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result WHERE conversation_result_id="
                + "(SELECT id FROM iqc_inspection_conversation_result WHERE task_id=?)", String.class, created.getId()))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
        var finding = mapper.readTree(jdbc.queryForObject("SELECT rr.finding_json FROM iqc_inspection_rule_result rr "
                + "JOIN iqc_inspection_conversation_result cr ON cr.id=rr.conversation_result_id WHERE cr.task_id=?",
                String.class, created.getId()));
        assertThat(finding.path("schemaVersion").asText()).isEqualTo("iqc-rule-observations-v2");
        verifyNoInteractions(harness.llm());
    }

    @Test
    void negativeApplicabilityCompletesDraftTrialWithoutInventingScore() throws Exception {
        var condition = new TrialApplicability(new SchemeDefinition.RuleReference("rule-3", 1), "需要贷款");
        var harness = newDraftTrial(true, false, false, false, condition);
        var frozen = mapper.readTree(harness.task().getRuleSnapshotJson()).path("schemeSnapshot");
        assertThat(frozen.path("release").path("definition").path("schemaVersion").asText())
                .isEqualTo(SchemeDefinition.JOINT_TASK_SCHEMA);
        assertThat(frozen.path("contentHash").asText()).isEqualTo(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(
                mapper.writeValueAsString(frozen.path("release"))));
        var queued = harness.service().queue(harness.task().getId());

        var completed = harness.service().run(queued.getId(), queued.getCurrentExecutionId());

        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(2);
        assertTrialItem(queued.getCurrentExecutionId(), "NOT_APPLICABLE", "NOT_APPLICABLE", null);
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result", String.class))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
        verifyNoInteractions(harness.llm());
    }

    @Test
    void releaseHistoryUsesRealVersionCursorAndPreservesDisabledFrozenStandards() throws Exception {
        var harness = newDraftTrial(true);
        createIsolatedSchemeVersionTable();
        String frozenJson = mapper.writeValueAsString(harness.release());
        String hash = io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(frozenJson);
        for (int number = 1; number <= 25; number++)
            jdbc.update("INSERT INTO iqc_inspection_scheme_version (id, scheme_id, version_no, source_draft_revision, "
                    + "source_trial_task_id, snapshot_json, content_hash) VALUES (?, 'scheme-1', ?, ?, ?, ?, ?)",
                    "history-" + number, number, number, harness.task().getId(), frozenJson, hash);
        jdbc.update("UPDATE iqc_inspection_scheme SET status = 'DISABLED', name = '当前修改后的草稿' WHERE id = 'scheme-1'");
        var dataScope = mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
        when(dataScope.canView(any(), any())).thenReturn(true);
        var dependencyResolver = mock(SchemeDependencyResolver.class);
        var service = new io.github.opensabre.iqc.scheme.InspectionSchemeService(schemeMapper, schemeVersionMapper,
                dependencyResolver, dataScope, mapper);

        var first = service.history("scheme-1", null);
        assertThat(first.versions()).hasSize(20);
        assertThat(first.versions().getFirst().versionNo()).isEqualTo(25);
        assertThat(first.nextBeforeVersion()).isEqualTo(6);
        // Label binding/value POs do not define value equality; compare the persisted protocol, not their identities.
        JsonNode returnedSnapshot = mapper.valueToTree(first.versions().getFirst().snapshot());
        assertThat(returnedSnapshot).isEqualTo(mapper.readTree(frozenJson));
        assertThat(first.versions().getFirst().contentHash()).isEqualTo(hash);
        assertThat(first.versions().getFirst().sourceTrialTaskId()).isEqualTo(harness.task().getId());
        var second = service.history("scheme-1", first.nextBeforeVersion());
        assertThat(second.versions()).extracting(io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleasedVersion::versionNo)
                .containsExactly(5, 4, 3, 2, 1);
        assertThat(second.nextBeforeVersion()).isNull();
        verifyNoInteractions(dependencyResolver);
        assertThat(count("iqc_inspection_scheme_version")).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_scheme WHERE id = 'scheme-1'", String.class)).isEqualTo("DISABLED");
        jdbc.update("UPDATE iqc_inspection_scheme_version SET content_hash = 'tampered' WHERE version_no = 1");
        assertThatThrownBy(() -> service.history("scheme-1", 2)).hasMessageContaining("校验失败");
    }

    @Test
    void disabledApplicabilityAfterQueueStopsBeforeDetection() throws Exception {
        var condition = new TrialApplicability(new SchemeDefinition.RuleReference("rule-3", 1), "房子");
        var harness = newDraftTrial(true, false, false, false, condition);
        var queued = harness.service().queue(harness.task().getId());
        jdbc.update("UPDATE iqc_quality_rule SET status = 'DISABLED' WHERE id = 'rule-3'");

        harness.service().executeAsync(queued.getId(), queued.getCurrentExecutionId());

        assertThat(taskMapper.selectById(queued.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(executionMapper.selectById(queued.getCurrentExecutionId()).getStatus()).isEqualTo("FAILED");
        assertThat(count("iqc_inspection_result")).isZero();
        assertThat(count("iqc_inspection_conversation_result")).isZero();
        verifyNoInteractions(harness.llm());
    }

    @Test
    void positiveApplicabilityUsesIndependentDeductionInRealDraftTrial() throws Exception {
        var condition = new TrialApplicability(new SchemeDefinition.RuleReference("rule-3", 1), "房子");
        var harness = newDraftTrial(true, false, false, false, condition);
        var queued = harness.service().queue(harness.task().getId());

        var completed = harness.service().run(queued.getId(), queued.getCurrentExecutionId());

        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(2);
        assertTrialItem(queued.getCurrentExecutionId(), "FAIL", "FINAL", "90");
        // A dependency gate never adds a second business scoring line.
        var scoring = mapper.readTree(jdbc.queryForObject(
                "SELECT scoring_result_json FROM iqc_inspection_conversation_result", String.class));
        assertThat(scoring.path("lines")).hasSize(1);
        verifyNoInteractions(harness.llm());
    }

    @Test
    void erroredLlmApplicabilityStaysPendingAndRetryPreservesOldResult() throws Exception {
        // Reuse the existing label detector as a condition: one frozen dependency, independent projections.
        var condition = new TrialApplicability(new SchemeDefinition.RuleReference("rule-2", 1), null);
        var harness = newDraftTrial(true, true, false, false, condition);
        var first = harness.service().queue(harness.task().getId());
        var partial = harness.service().run(first.getId(), first.getCurrentExecutionId());

        assertThat(partial.getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(partial.getFailedMessages()).isEqualTo(1);
        assertTrialItem(first.getCurrentExecutionId(), "ERROR", "PENDING", null);
        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenReturn(knownFalseHouse("message-1"));

        var retry = harness.service().queue(partial.getId());
        var recovered = harness.service().run(retry.getId(), retry.getCurrentExecutionId());

        assertThat(recovered.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(recovered.getFailedMessages()).isZero();
        assertThat(retry.getCurrentExecutionId()).isNotEqualTo(first.getCurrentExecutionId());
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(2);
        assertTrialItem(first.getCurrentExecutionId(), "ERROR", "PENDING", null);
        assertTrialItem(retry.getCurrentExecutionId(), "NOT_APPLICABLE", "NOT_APPLICABLE", null);
        assertThat(jdbc.queryForObject("""
                SELECT l.value_json FROM iqc_inspection_label_result l
                JOIN iqc_inspection_conversation_result c ON c.id = l.conversation_result_id
                WHERE c.execution_id = ?
                """, String.class, retry.getCurrentExecutionId()))
                .contains("\"status\":\"KNOWN\"", "\"value\":false");
    }

    private void assertTrialItem(String executionId, String itemStatus, String scoreStatus, String score) throws Exception {
        String predicate = " FROM iqc_inspection_conversation_result WHERE execution_id = ?";
        var items = mapper.readTree(jdbc.queryForObject("SELECT business_item_results_json" + predicate,
                String.class, executionId));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("status").asText()).isEqualTo(itemStatus);
        assertThat(jdbc.queryForObject("SELECT score_status" + predicate, String.class, executionId))
                .isEqualTo(scoreStatus);
        var actual = jdbc.queryForObject("SELECT final_score" + predicate, java.math.BigDecimal.class, executionId);
        if (score == null) assertThat(actual).isNull();
        else assertThat(actual).isEqualByComparingTo(score);
    }

    @Test
    void failedNonScoringLlmLabelRetainsFinalQualityScoreAndReportsPartialFailure() throws Exception {
        var harness = newDraftTrial(true, true);
        var queued = harness.service().queue(harness.task().getId());

        var completed = harness.service().run(queued.getId(), queued.getCurrentExecutionId());

        assertThat(completed.getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(completed.getFailedMessages()).isEqualTo(1);
        assertThat(executionMapper.selectById(queued.getCurrentExecutionId()).getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_item", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT result_status FROM iqc_inspection_result", String.class))
                .isEqualTo("PARTIAL_ERROR");
        assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result", String.class))
                .isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result", java.math.BigDecimal.class))
                .isEqualByComparingTo("90");
        assertThat(count("iqc_inspection_rule_result")).isEqualTo(2);
        var qualityItems = mapper.readTree(jdbc.queryForObject(
                "SELECT business_item_results_json FROM iqc_inspection_conversation_result", String.class));
        assertThat(qualityItems).hasSize(1);
        assertThat(qualityItems.get(0).path("status").asText()).isEqualTo("FAIL");
        var labelValue = mapper.readTree(jdbc.queryForObject(
                "SELECT value_json FROM iqc_inspection_label_result", String.class));
        assertThat(labelValue.path("status").asText()).isEqualTo("ERROR");
        assertThat(labelValue.has("value")).isFalse();
        verify(harness.llm()).evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString());
    }

    @Test
    void failedLlmLabelRetryProducesKnownValueWithoutErasingPreviousResultOrScore() throws Exception {
        var harness = newDraftTrial(true, true);
        var firstQueued = harness.service().queue(harness.task().getId());
        String firstExecutionId = firstQueued.getCurrentExecutionId();
        String frozenSnapshot = firstQueued.getRuleSnapshotJson();
        var failed = harness.service().run(firstQueued.getId(), firstExecutionId);
        assertThat(failed.getStatus()).isEqualTo("PARTIAL_FAILED");

        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenReturn(knownFalseHouse("message-1"));
        var secondQueued = harness.service().queue(failed.getId());
        String secondExecutionId = secondQueued.getCurrentExecutionId();
        assertThat(secondExecutionId).isNotEqualTo(firstExecutionId);
        assertThat(secondQueued.getAttemptCount()).isEqualTo(2);
        assertThat(secondQueued.getRuleSnapshotJson()).isEqualTo(frozenSnapshot);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id = ?", Integer.class,
                secondExecutionId)).isEqualTo(1);

        var recovered = harness.service().run(secondQueued.getId(), secondExecutionId);

        assertThat(recovered.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(recovered.getProcessedMessages()).isEqualTo(1);
        assertThat(recovered.getFailedMessages()).isZero();
        assertThat(executionMapper.selectById(firstExecutionId).getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(executionMapper.selectById(secondExecutionId).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_item WHERE execution_id = ?", String.class,
                firstExecutionId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_item WHERE execution_id = ?", String.class,
                secondExecutionId)).isEqualTo("SUCCEEDED");
        assertThat(count("iqc_inspection_result")).isEqualTo(2);
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(2);
        assertThat(count("iqc_inspection_label_result")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iqc_inspection_evidence e
                JOIN iqc_inspection_rule_result r ON r.id = e.rule_result_id
                JOIN iqc_inspection_conversation_result c ON c.id = r.conversation_result_id
                WHERE c.execution_id = ? AND r.rule_id = 'rule-2'
                """, Integer.class, secondExecutionId)).isEqualTo(1);
        for (String executionId : List.of(firstExecutionId, secondExecutionId)) {
            assertThat(jdbc.queryForObject("SELECT score_status FROM iqc_inspection_conversation_result WHERE execution_id = ?",
                    String.class, executionId)).isEqualTo("FINAL");
            assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE execution_id = ?",
                    java.math.BigDecimal.class, executionId)).isEqualByComparingTo("90");
        }
        String labelByExecution = "SELECT l.value_json FROM iqc_inspection_label_result l "
                + "JOIN iqc_inspection_conversation_result c ON c.id = l.conversation_result_id WHERE c.execution_id = ?";
        var oldLabel = mapper.readTree(jdbc.queryForObject(labelByExecution, String.class, firstExecutionId));
        var newLabel = mapper.readTree(jdbc.queryForObject(labelByExecution, String.class, secondExecutionId));
        assertThat(oldLabel.path("status").asText()).isEqualTo("ERROR");
        assertThat(oldLabel.has("value")).isFalse();
        assertThat(newLabel.path("status").asText()).isEqualTo("KNOWN");
        assertThat(newLabel.path("value").booleanValue()).isFalse();
    }

    @Test
    void partialBatchRetryReprocessesOnlyFailedConversationAndKeepsSuccessfulResult() throws Exception {
        var harness = newDraftTrial(true, true, true);
        var firstQueued = harness.service().queue(harness.task().getId());
        String firstExecutionId = firstQueued.getCurrentExecutionId();

        var partial = harness.service().run(firstQueued.getId(), firstExecutionId);

        assertThat(partial.getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(partial.getTotalMessages()).isEqualTo(2);
        assertThat(partial.getProcessedMessages()).isEqualTo(2);
        assertThat(partial.getFailedMessages()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_item WHERE message_id = 'message-1'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_item WHERE message_id = 'message-2'", String.class))
                .isEqualTo("SUCCEEDED");
        String successfulResultId = jdbc.queryForObject("""
                SELECT id FROM iqc_inspection_conversation_result
                WHERE execution_id = ? AND conversation_id = 'conversation-2'
                """, String.class, firstExecutionId);

        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenAnswer(invocation -> {
                    List<ConversationMessage> context = invocation.getArgument(0);
                    return knownFalseHouse(context.getFirst().getId());
                });
        var retry = harness.service().queue(partial.getId());
        String secondExecutionId = retry.getCurrentExecutionId();
        assertThat(retry.getProcessedMessages()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT message_id FROM iqc_task_item WHERE execution_id = ?", String.class,
                secondExecutionId)).containsExactly("message-1");

        var completed = harness.service().run(retry.getId(), secondExecutionId);

        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getProcessedMessages()).isEqualTo(2);
        assertThat(completed.getFailedMessages()).isZero();
        assertThat(executionMapper.selectById(firstExecutionId).getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(executionMapper.selectById(secondExecutionId).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(count("iqc_inspection_result")).isEqualTo(3);
        assertThat(count("iqc_inspection_conversation_result")).isEqualTo(3);
        assertThat(count("iqc_inspection_label_result")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT id FROM iqc_inspection_conversation_result WHERE conversation_id = 'conversation-2'
                """, String.class)).isEqualTo(successfulResultId);
        String recoveredResultId = jdbc.queryForObject("""
                SELECT id FROM iqc_inspection_conversation_result
                WHERE execution_id = ? AND conversation_id = 'conversation-1'
                """, String.class, secondExecutionId);
        assertThat(jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE id = ?",
                java.math.BigDecimal.class, recoveredResultId)).isEqualByComparingTo("90");
        var label = mapper.readTree(jdbc.queryForObject("""
                SELECT value_json FROM iqc_inspection_label_result WHERE conversation_result_id = ?
                """, String.class, recoveredResultId));
        assertThat(label.path("status").asText()).isEqualTo("KNOWN");
        assertThat(label.path("value").booleanValue()).isFalse();
    }

    @Test
    void reviewedTrialUsesRecoveredExecutionForPublicationEvenWhenOldResultTimestampIsLater() throws Exception {
        var harness = newDraftTrial(true, true, true);
        var firstQueued = harness.service().queue(harness.task().getId());
        var partial = harness.service().run(firstQueued.getId(), firstQueued.getCurrentExecutionId());
        assertThat(partial.getStatus()).isEqualTo("PARTIAL_FAILED");
        when(harness.llm().evaluateConversation(anyList(), any(JsonNode.class), any(JsonNode.class), anyString()))
                .thenAnswer(invocation -> {
                    List<ConversationMessage> context = invocation.getArgument(0);
                    return knownFalseHouse(context.getFirst().getId());
                });
        var retry = harness.service().queue(partial.getId());
        var recovered = harness.service().run(retry.getId(), retry.getCurrentExecutionId());
        assertThat(recovered.getStatus()).isEqualTo("SUCCEEDED");
        jdbc.update("""
                UPDATE iqc_inspection_conversation_result SET created_time = '2030-01-01 00:00:00'
                WHERE execution_id = ? AND conversation_id = 'conversation-1'
                """, firstQueued.getCurrentExecutionId());
        assertThat(canonicalResults.selectLatestForTask(recovered.getId())).hasSize(2)
                .filteredOn(value -> "conversation-1".equals(value.getConversationId())).singleElement()
                .satisfies(value -> assertThat(value.getExecutionId()).isEqualTo(retry.getCurrentExecutionId()));
        assertThat(canonicalResults.selectLatestInWindow(List.of(recovered.getId()), null, null)).hasSize(2)
                .filteredOn(value -> "conversation-1".equals(value.getConversationId())).singleElement()
                .satisfies(value -> assertThat(value.getExecutionId()).isEqualTo(retry.getCurrentExecutionId()));

        var schemes = mock(io.github.opensabre.iqc.scheme.InspectionSchemeService.class);
        when(schemes.preview("scheme-1", 1)).thenReturn(harness.release());
        var scope = mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
        when(scope.canView(nullable(String.class), nullable(String.class))).thenReturn(true);
        var labelMapper = mock(io.github.opensabre.iqc.label.dao.InsightLabelMapper.class);
        var groupMapper = mock(io.github.opensabre.iqc.label.dao.LabelGroupMapper.class);
        var categoryMapper = mock(io.github.opensabre.iqc.label.dao.LabelCategoryMapper.class);
        var labelReviews = mock(io.github.opensabre.iqc.label.LabelResultReviewService.class);
        when(labelMapper.selectBatchIds(any())).thenReturn(List.of());
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of());
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of());
        when(labelReviews.effectiveForTask(any(), any(), any())).thenReturn(java.util.Map.of());
        var labels = new io.github.opensabre.iqc.label.LabelResultQueryService(taskMapper, canonicalResults,
                persistedLabels, labelMapper, groupMapper, categoryMapper, conversations,
                persistedEvidence, mapper, scope, persistedRuleResults, labelReviews);
        assertThat(labels.listByTask(recovered.getId())).hasSize(2)
                .filteredOn(value -> "conversation-1".equals(value.conversationId())).singleElement()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo("KNOWN");
                    assertThat(value.valueJson()).contains("\"value\":false");
                    assertThat(value.evidenceJson()).contains("message-1");
                });
        var batch = new BatchResultQueryService(taskMapper, conversations, messages, observations,
                scope, mapper, canonicalResults, executionMapper);
        when(scope.canViewAll()).thenReturn(true);
        var firstPage = batch.businessPage(1, 1, recovered.getId(), null, null, null, null);
        var secondPage = batch.businessPage(2, 1, recovered.getId(), null, null, null, null);
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(secondPage.total()).isEqualTo(2);
        assertThat(firstPage.records()).hasSize(1);
        assertThat(recovered.getName()).isNotBlank();
        assertThat(firstPage.records().getFirst().taskName()).isEqualTo(recovered.getName());
        assertThat(secondPage.records()).hasSize(1);
        assertThat(firstPage.records().getFirst().id()).isNotEqualTo(secondPage.records().getFirst().id());
        assertThat(java.util.stream.Stream.concat(firstPage.records().stream(), secondPage.records().stream()).toList())
                .allSatisfy(value -> assertThat(value.finalScore()).isEqualByComparingTo("90"));
        assertThat(batch.businessPage(1, 20, recovered.getId(), null, null, null, java.math.BigDecimal.TEN).total())
                .isZero();
        assertThat(batch.summary(recovered.getId()).averageScore()).isEqualByComparingTo("90");
        assertThat(batch.summary(recovered.getId()).conversations()).hasSize(2)
                .allSatisfy(value -> {
                    assertThat(value.scoreStatus()).isEqualTo("FINAL");
                    assertThat(value.averageScore()).isEqualByComparingTo("90");
                    assertThat(value.resultCount()).isEqualTo(1);
                    assertThat(value.errorCount()).isZero();
                });
        assertThat(batch.conversationDetail(recovered.getId(), "conversation-1").results()).singleElement()
                .satisfies(value -> assertThat(value.getExecutionId()).isEqualTo(retry.getCurrentExecutionId()));
        assertThat(batch.exportSchemeCsv(recovered.getId()).lines().skip(1).toList()).hasSize(2)
                .filteredOn(value -> value.contains("\"conversation-1\","))
                .singleElement().satisfies(value -> assertThat(value).contains(retry.getCurrentExecutionId()));
        assertThat(results.hierarchy(recovered.getId(), "conversation-1").conversation().getExecutionId())
                .isEqualTo(retry.getCurrentExecutionId());
        // Real persisted items, reviews and score validation after retry; only authorization is stubbed.
        var businessReviews = new io.github.opensabre.iqc.quality.BusinessItemReviewService(
                persistedReviews, canonicalResults,
                taskMapper, messages, scope, mapper);
        var reports = new io.github.opensabre.iqc.quality.BusinessQualityReportService(
                taskMapper, canonicalResults, businessReviews, scope, mapper, executionMapper);
        // Manually wired services use explicit boundaries matching their production transaction contracts.
        var reportTransaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        reportTransaction.setReadOnly(true);
        reportTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        java.util.function.Supplier<io.github.opensabre.iqc.quality.BusinessQualityReportService.Report> reportQuery =
                () -> reportTransaction.execute(ignored -> reports.report(List.of(recovered.getId())));
        var reportGroup = reportQuery.get().groups().getFirst();
        assertThat(reportGroup.machine().resultCount()).isEqualTo(2);
        assertThat(reportGroup.machine().averageScore()).isEqualByComparingTo("90");
        assertThat(reportGroup.items()).singleElement().satisfies(item -> {
            assertThat(item.machine().resultCount()).isEqualTo(2);
            assertThat(item.machine().failCount()).isEqualTo(2);
            assertThat(item.machine().errorCount()).isZero();
            assertThat(item.machine().failureRate()).isEqualByComparingTo("100");
            assertThat(item.reviewed().resultCount()).isZero();
            assertThat(item.reviewed().failureRate()).isNull();
        });
        var firstRunReport = reportTransaction.execute(ignored -> reports.report(
                List.of(recovered.getId()), null, "FIRST_PER_CONVERSATION"));
        var latestRunReport = reportTransaction.execute(ignored -> reports.report(
                List.of(recovered.getId()), null, "LATEST_PER_CONVERSATION"));
        String exactExecutionSelection = mapper.writeValueAsString(
                Map.of(recovered.getId(), firstQueued.getCurrentExecutionId()));
        var exactRunReport = reportTransaction.execute(ignored -> reports.report(List.of(recovered.getId()),
                exactExecutionSelection, "SELECTED_RUNS"));
        assertThat(firstRunReport.conversationCount()).isEqualTo(2);
        assertThat(firstRunReport.groups()).singleElement().satisfies(group -> {
            assertThat(group.executionIds()).containsExactly(firstQueued.getCurrentExecutionId());
        });
        assertThat(latestRunReport.conversationCount()).isEqualTo(2);
        assertThat(latestRunReport.groups()).singleElement().satisfies(group -> {
            assertThat(group.executionIds()).containsExactlyInAnyOrder(firstQueued.getCurrentExecutionId(), retry.getCurrentExecutionId());
        });
        assertThat(exactRunReport.conversationCount()).isEqualTo(2);
        assertThat(exactRunReport.groups()).singleElement().satisfies(group -> {
            assertThat(group.executionIds()).containsExactly(firstQueued.getCurrentExecutionId());
        });
        // A malformed machine projection cannot be silently included in item statistics.
        var recoveredResult = canonicalResults.selectLatestForTaskConversation(recovered.getId(), "conversation-1");
        String savedItems = recoveredResult.getBusinessItemResultsJson();
        jdbc.update("UPDATE iqc_inspection_conversation_result SET business_item_results_json = ? WHERE id = ?",
                savedItems.replace("\"FAIL\"", "\"PASS\""), recoveredResult.getId());
        assertThatThrownBy(reportQuery::get).hasMessageContaining("机器评分快照不一致");
        jdbc.update("UPDATE iqc_inspection_conversation_result SET business_item_results_json = ? WHERE id = ?",
                savedItems, recoveredResult.getId());
        when(scope.owner()).thenReturn("quality-reviewer");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var review = transaction.execute(ignored -> businessReviews.request(recoveredResult.getId(), 0,
                "report-review-first-0001", "核对原文"));
        assertThat(transaction.execute(ignored -> businessReviews.request(recoveredResult.getId(), 0,
                "report-review-first-0001", "核对原文")).getId()).isEqualTo(review.getId());
        String itemCode = mapper.readTree(savedItems).get(0).path("itemCode").asText();
        var decisions = List.of(new io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator.Decision(
                recoveredResult.getId(), itemCode, InspectionScoring.ItemStatus.FAIL,
                InspectionScoring.ItemStatus.PASS, "原文核对后满足标准", List.of("message-1")));
        var completed = transaction.execute(ignored -> businessReviews.decide(review.getId(), 1, false, decisions, "确认修正"));
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(transaction.execute(ignored -> businessReviews.decide(review.getId(), 1, false, decisions,
                "确认修正")).getId()).isEqualTo(completed.getId());
        assertThat(count("iqc_result_review")).isEqualTo(1);
        assertThat(canonicalResults.selectById(recoveredResult.getId()).getBusinessItemResultsJson()).isEqualTo(savedItems);
        var reviewedGroup = reportQuery.get().groups().getFirst();
        assertThat(reviewedGroup.machine().averageScore()).isEqualByComparingTo("90");
        assertThat(reviewedGroup.reviewed().averageScore()).isEqualByComparingTo("100");
        assertThat(reviewedGroup.reviewed().resultCount()).isEqualTo(1);
        assertThat(reviewedGroup.missingReviewCount()).isEqualTo(1);
        assertThat(reviewedGroup.items().getFirst().reviewed().passCount()).isEqualTo(1);
        assertThat(reviewedGroup.items().getFirst().reviewed().failureRate()).isEqualByComparingTo("0");
        var corruptReview = mapper.readTree(completed.getReviewedResultJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) corruptReview.path("reviewed").path("scoring")).put("finalScore", 0);
        jdbc.update("UPDATE iqc_result_review SET reviewed_result_json = ? WHERE id = ?", corruptReview.toString(), completed.getId());
        assertThatThrownBy(reportQuery::get).hasMessageContaining("复核项目或评分快照不一致");
        jdbc.update("UPDATE iqc_result_review SET reviewed_result_json = ? WHERE id = ?", completed.getReviewedResultJson(), completed.getId());
        var pendingReview = transaction.execute(ignored -> businessReviews.request(recoveredResult.getId(), 1,
                "report-review-pending-0002", "再次确认"));
        var pendingGroup = reportQuery.get().groups().getFirst();
        assertThat(pendingGroup.pendingReviewCount()).isEqualTo(1);
        assertThat(pendingGroup.reviewed()).isEqualTo(reviewedGroup.reviewed());
        assertThat(pendingGroup.items()).isEqualTo(reviewedGroup.items());
        transaction.executeWithoutResult(ignored -> businessReviews.decide(pendingReview.getId(), 2, true, List.of(), "退回补充说明"));
        var rejectedGroup = reportQuery.get().groups().getFirst();
        assertThat(rejectedGroup.pendingReviewCount()).isZero();
        assertThat(rejectedGroup.reviewed()).isEqualTo(reviewedGroup.reviewed());
        assertThat(rejectedGroup.items()).isEqualTo(reviewedGroup.items());
        assertThat(businessReviews.history(recoveredResult.getId())).extracting(value -> value.getStatus())
                .containsExactly("REJECTED", "COMPLETED");
        var publication = new SchemePublicationService(schemes, mock(InspectionTaskService.class), taskMapper,
                canonicalResults, persistedLabels, labels, scope, mapper);

        publication.publish("scheme-1", 1, recovered.getId(), true);

        verify(schemes).publishValidated("scheme-1", 1, recovered.getId(),
                io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(
                        mapper.writeValueAsString(harness.release().forTaskSnapshot())));
    }

    @Test
    void partialConversationRetryShowsCurrentObservationPerMessageAcrossAttempts() {
        jdbc.update("INSERT INTO iqc_conversation (id, source_file_name, message_count, source_fingerprint, status) VALUES (?, ?, ?, 'mixed-fixture', 'IMPORTED')",
                "conversation-1", "mixed.txt", 2);
        for (int sequence = 1; sequence <= 2; sequence++) {
            insertFixtureMessage("message-" + sequence, "conversation-1", sequence, "customer", "content-" + sequence);
        }
        jdbc.update("""
                INSERT INTO iqc_inspection_task
                    (id, conversation_id, rule_snapshot_json, current_execution_id, status,
                     total_messages, processed_messages, failed_messages, name)
                VALUES (?, ?, ?, ?, ?, 2, 2, 0, 'mixed fixture')
                """, "task-mixed", "conversation-1", snapshot().toString(), "execution-2", "SUCCEEDED");
        jdbc.update("INSERT INTO iqc_task_execution (id, task_id, attempt_no, status) VALUES (?, ?, ?, ?)",
                "execution-1", "task-mixed", 1, "PARTIAL_FAILED");
        jdbc.update("INSERT INTO iqc_task_execution (id, task_id, attempt_no, status) VALUES (?, ?, ?, ?)",
                "execution-2", "task-mixed", 2, "SUCCEEDED");
        jdbc.update("""
                INSERT INTO iqc_inspection_result (id, task_id, execution_id, conversation_id, message_id, result_status, created_time, speaker_role, reason)
                VALUES ('old-success', 'task-mixed', 'execution-1', 'conversation-1', 'message-1', 'HIT', '2030-01-01 00:00:00', 'customer', 'fixture'),
                       ('old-failure', 'task-mixed', 'execution-1', 'conversation-1', 'message-2', 'PARTIAL_ERROR', '2030-01-01 00:00:00', 'customer', 'fixture'),
                       ('new-success', 'task-mixed', 'execution-2', 'conversation-1', 'message-2', 'HIT', '2026-01-01 00:00:00', 'customer', 'fixture')
                """);
        jdbc.update("""
                INSERT INTO iqc_inspection_conversation_result
                    (id, task_id, execution_id, conversation_id, score_status, final_score, created_time, result_status, reason)
                VALUES ('old-decision', 'task-mixed', 'execution-1', 'conversation-1', 'PENDING', NULL, '2030-01-01 00:00:00', 'PARTIAL_ERROR', 'fixture'),
                       ('new-decision', 'task-mixed', 'execution-2', 'conversation-1', 'FINAL', 90, '2026-01-01 00:00:00', 'HIT', 'fixture')
                """);
        var scope = mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
        when(scope.canView(nullable(String.class), nullable(String.class))).thenReturn(true);
        var batch = new BatchResultQueryService(taskMapper, conversations, messages, observations,
                scope, mapper, canonicalResults, executionMapper);

        var row = batch.summary("task-mixed").conversations().getFirst();

        assertThat(row.scoreStatus()).isEqualTo("FINAL");
        assertThat(row.resultCount()).isEqualTo(2);
        assertThat(row.errorCount()).isZero();
        assertThat(batch.conversationDetail("task-mixed", "conversation-1").results())
                .extracting(InspectionResult::getId).containsExactlyInAnyOrder("old-success", "new-success");
    }

    @Test
    void partialMessagePersistenceFailureRetriesOnlyFailedMessageAndKeepsCompleteConversationReadout() throws Exception {
        var harness = newDraftTrial(true, false, false, true);
        jdbc.execute("ALTER TABLE iqc_inspection_result ADD CONSTRAINT reject_second_message CHECK (message_id <> 'message-extra')");
        var firstQueued = harness.service().queue(harness.task().getId());

        var partial = harness.service().run(firstQueued.getId(), firstQueued.getCurrentExecutionId());

        assertThat(partial.getStatus()).isEqualTo("PARTIAL_FAILED");
        assertThat(partial.getTotalMessages()).isEqualTo(2);
        assertThat(partial.getProcessedMessages()).isEqualTo(2);
        assertThat(partial.getFailedMessages()).isEqualTo(1);
        String preservedId = jdbc.queryForObject("SELECT id FROM iqc_inspection_result WHERE message_id = 'message-1'", String.class);
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_item WHERE message_id = 'message-extra'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM iqc_task_item WHERE message_id = 'message-extra'", String.class))
                .isNotBlank().hasSizeLessThanOrEqualTo(1000);
        jdbc.execute("ALTER TABLE iqc_inspection_result DROP "
                + (usesMigratedSchema() ? "CHECK" : "CONSTRAINT") + " reject_second_message");

        var retry = harness.service().queue(partial.getId());

        assertThat(retry.getProcessedMessages()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT message_id FROM iqc_task_item WHERE execution_id = ?", String.class,
                retry.getCurrentExecutionId())).containsExactly("message-extra");
        var recovered = harness.service().run(retry.getId(), retry.getCurrentExecutionId());

        assertThat(recovered.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(recovered.getProcessedMessages()).isEqualTo(2);
        assertThat(recovered.getFailedMessages()).isZero();
        assertThat(count("iqc_inspection_result")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id FROM iqc_inspection_result WHERE message_id = 'message-1'", String.class))
                .isEqualTo(preservedId);
        var canonical = canonicalResults.selectLatestForTaskConversation(recovered.getId(), "conversation-1");
        assertThat(canonical.getExecutionId()).isEqualTo(retry.getCurrentExecutionId());
        assertThat(canonical.getScoreStatus()).isEqualTo("FINAL");
        assertThat(canonical.getFinalScore()).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("SELECT value_json FROM iqc_inspection_label_result WHERE conversation_result_id = ?",
                String.class, canonical.getId())).contains("\"status\":\"KNOWN\"", "\"value\":false");
        var scope = mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
        when(scope.canView(nullable(String.class), nullable(String.class))).thenReturn(true);
        var batch = new BatchResultQueryService(taskMapper, conversations, messages, observations,
                scope, mapper, canonicalResults, executionMapper);
        var detail = batch.conversationDetail(recovered.getId(), "conversation-1");
        assertThat(detail.summary().resultCount()).isEqualTo(2);
        assertThat(detail.summary().errorCount()).isZero();
        assertThat(detail.results()).extracting(InspectionResult::getMessageId)
                .containsExactlyInAnyOrder("message-1", "message-extra");
        assertThat(detail.results()).extracting(InspectionResult::getExecutionId)
                .containsExactlyInAnyOrder(firstQueued.getCurrentExecutionId(), retry.getCurrentExecutionId());
        verifyNoInteractions(harness.llm());
    }

    @Test
    void disabledSchemeAfterQueueFailsBeforeDraftTrialDetection() throws Exception {
        var harness = newDraftTrial();
        var queued = harness.service().queue(harness.task().getId());
        jdbc.update("UPDATE iqc_inspection_scheme SET status = 'DISABLED' WHERE id = 'scheme-1'");

        harness.service().executeAsync(queued.getId(), queued.getCurrentExecutionId());

        assertThat(taskMapper.selectById(queued.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(executionMapper.selectById(queued.getCurrentExecutionId()).getStatus()).isEqualTo("FAILED");
        assertThat(count("iqc_task_item")).isEqualTo(1);
        assertThat(count("iqc_inspection_result")).isZero();
        assertThat(count("iqc_inspection_conversation_result")).isZero();
        assertThat(count("iqc_inspection_label_result")).isZero();
        verifyNoInteractions(harness.llm());
    }

    @Test
    void labelInsertFailureRollsBackConversationRuleAndEvidenceTogether() {
        jdbc.execute("ALTER TABLE iqc_inspection_label_result ADD CONSTRAINT reject_test_value CHECK (value_code <> 'owns')");

        assertThatThrownBy(() -> results.materialize(task(), "execution-failed", snapshot(),
                List.of(message()), List.of(observation()))).isInstanceOf(RuntimeException.class);

        assertThat(count("iqc_inspection_conversation_result")).isZero();
        assertThat(count("iqc_inspection_rule_result")).isZero();
        assertThat(count("iqc_inspection_evidence")).isZero();
        assertThat(count("iqc_inspection_label_result")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private InspectionTask task() {
        var task = new InspectionTask(); task.setId("task-joint");
        task.setLabelScopeSnapshotJson("""
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":1,"targetRole":"customer",
                "bindings":[{"ruleId":"rule-1","ruleVersionNo":1}],
                "values":[{"valueCode":"owns","valueType":"BOOLEAN"}]}]}
                """);
        return task;
    }

    private JsonNode snapshot() {
        var root = mapper.createObjectNode();
        root.putArray("rules").addObject().put("id", "rule-1").put("versionNo", 1)
                .put("ruleType", "LLM").put("targetRole", "customer")
                .putArray("labelFactTargets").addObject().put("labelId", "house");
        var item = new SchemeDefinition.Item("check", "住房信息核查",
                new SchemeDefinition.RuleReference("rule-1", 1), SchemeDefinition.HitMeaning.COMPLIANCE);
        var policy = new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION,
                100, 60, List.of(new InspectionScoring.Item("check", 10, false)));
        root.putObject("schemeSnapshot").putObject("release").set("definition", mapper.valueToTree(
                new SchemeDefinition("iqc-scheme-v2", List.of(item), null, policy)));
        return root;
    }

    private JsonNode labelOnlySnapshot() {
        var root = snapshot().deepCopy();
        var policy = new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION,
                100, 60, List.of());
        var definition = new SchemeDefinition("iqc-scheme-v2", List.of(), null, policy, null,
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("house", 1)));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/schemeSnapshot/release"))
                .set("definition", mapper.valueToTree(definition));
        return root;
    }

    private ConversationMessage message() {
        var message = new ConversationMessage(); message.setId("message-1"); message.setConversationId("conversation-1");
        message.setSequenceNo(1); message.setSpeakerRole("customer"); message.setContent("我没有房子");
        return message;
    }

    private InspectionResult observation() {
        var result = new InspectionResult(); result.setMessageId("message-1"); result.setResultStatus("HIT");
        result.setRuleBreakdownJson("[{\"ruleId\":\"rule-1\",\"status\":\"HIT\"}]");
        result.setFindingJson("""
                {"ruleFindings":{"rule-1":{"schemaVersion":"iqc-label-facts-v2","facts":[
                {"labelId":"house","valueCode":"owns","ruleId":"rule-1","subjectKind":"CURRENT_PARTICIPANT",
                "subjectRole":"customer","value":false,"evidence":[{"messageId":"message-1","text":"没有房子"}]}]}}}
                """);
        result.setEvidenceJson("[{\"ruleId\":\"rule-1\",\"messageId\":\"message-1\",\"text\":\"没有房子\"}]");
        return result;
    }
}
