package io.github.opensabre.iqc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.ratelimit.RateLimitDecision;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import io.github.opensabre.security.token.InternalTokenConstants;
import io.github.opensabre.security.token.InternalTokenRequest;
import io.github.opensabre.security.token.InternalTokenService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Exercises signed inbound identity and SQL task scope for review and business-result reads. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.config.import=optional:",
        "spring.datasource.url=jdbc:h2:mem:iqc_label_review_http;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class LabelReviewQueueHttpTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InternalTokenService tokens;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private IqcOrganizationClient organization;
    @MockitoBean private GovernanceRateLimiter rateLimiter;

    @BeforeAll
    void fixture() {
        jdbc.execute("""
                CREATE TABLE iqc_inspection_task (
                    id varchar(64) PRIMARY KEY, conversation_id varchar(64), name varchar(100), task_type varchar(32),
                    conversation_ids_json clob, selection_filter_json clob, concurrency_limit int, scheduled_time timestamp,
                    agent_id varchar(64), rule_set_id varchar(64), rule_ids_json clob, agent_snapshot_json clob,
                    rule_snapshot_json clob, label_scope_snapshot_json clob, run_count int, confidence_threshold decimal(10,4),
                    auto_expand_enabled boolean, auto_expand_prompt clob, queue_priority bigint, pause_requested boolean,
                    cancel_requested boolean, status varchar(32), total_messages int, processed_messages int,
                    failed_messages int, current_execution_id varchar(64), attempt_count int, owner_group_id varchar(64),
                    created_by varchar(64), created_time timestamp, updated_by varchar(64), updated_time timestamp)
                """);
        jdbc.execute("CREATE TABLE iqc_inspection_conversation_result (id varchar(64) PRIMARY KEY, task_id varchar(64), conversation_id varchar(64), created_time timestamp)");
        jdbc.execute("CREATE TABLE iqc_task_execution (id varchar(64) PRIMARY KEY, task_id varchar(64), attempt_no int)");
        jdbc.execute("CREATE TABLE iqc_inspection_label_result (id varchar(64) PRIMARY KEY, conversation_result_id varchar(64), label_id varchar(64), label_version_no int, value_code varchar(64))");
        jdbc.execute("CREATE TABLE iqc_result_review (id varchar(64) PRIMARY KEY, target_type varchar(32), label_result_id varchar(64), review_revision int, status varchar(32), request_comment varchar(100), created_by varchar(64), created_time timestamp)");
        jdbc.update("""
                INSERT INTO iqc_inspection_task
                    (id, name, status, created_by, owner_group_id, label_scope_snapshot_json)
                VALUES (?, ?, 'SUCCEEDED', ?, ?, ?), (?, ?, 'SUCCEEDED', ?, ?, ?), (?, ?, 'SUCCEEDED', ?, ?, ?)
                """,
                "own", "本人任务", "alice", "g1", null,
                "team", "同组任务", "bob", "g1", "{\"schemaVersion\":\"2.0\",\"labels\":[{\"id\":\"house\",\"versionNo\":2,\"name\":\"是否有房\",\"values\":[{\"valueCode\":\"owned\",\"description\":\"客户拥有房产\"}]}]}",
                "foreign", "外组任务", "eve", "g2", null);
        jdbc.update("INSERT INTO iqc_inspection_conversation_result VALUES ('c1','own','conversation','2026-09-01 00:00:00'),('c2','team','conversation','2026-09-01 00:00:00'),('c3','foreign','conversation','2026-09-01 00:00:00')");
        jdbc.execute("ALTER TABLE iqc_inspection_conversation_result ADD execution_id varchar(64)");
        jdbc.update("INSERT INTO iqc_inspection_label_result VALUES ('l1','c1','house',2,'owned'),('l2','c2','house',2,'owned'),('l3','c3','house',2,'owned')");
        jdbc.update("INSERT INTO iqc_result_review VALUES ('r1','LABEL','l1',1,'PENDING','本人结果','other','2026-09-01 00:00:00'),('r2','LABEL','l2',1,'PENDING','同组结果','other','2026-09-02 00:00:00'),('r3','LABEL','l3',1,'PENDING','申请人不是权限依据','alice','2026-09-03 00:00:00')");
        // Extend this isolated fixture rather than introducing another application/test persistence layer.
        jdbc.execute("ALTER TABLE iqc_inspection_conversation_result ADD score_status varchar(32)");
        jdbc.execute("ALTER TABLE iqc_inspection_conversation_result ADD final_score decimal(10,2)");
        jdbc.execute("ALTER TABLE iqc_inspection_conversation_result ADD business_item_results_json clob");
        jdbc.execute("ALTER TABLE iqc_inspection_conversation_result ADD risk_level varchar(32)");
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
        jdbc.update("INSERT INTO iqc_conversation (id, source_file_name) VALUES ('conversation','隔离测试会话')");
        jdbc.update("INSERT INTO iqc_task_execution VALUES ('own-1','own',1),('own-2','own',2),('team-2','team',2),('foreign-2','foreign',2)");
        jdbc.update("UPDATE iqc_inspection_conversation_result SET execution_id='own-2', score_status='FINAL', final_score=0, business_item_results_json='[{\"status\":\"FAIL\"}]', risk_level='HIGH' WHERE id='c1'");
        jdbc.update("UPDATE iqc_inspection_conversation_result SET execution_id='team-2', score_status='NOT_APPLICABLE', business_item_results_json='[]', risk_level='LOW', created_time='2026-09-02 00:00:00' WHERE id='c2'");
        jdbc.update("UPDATE iqc_inspection_conversation_result SET execution_id='foreign-2', score_status='FINAL', final_score=95.50, business_item_results_json='[{\"status\":\"PASS\"}]', risk_level='LOW', created_time='2026-09-03 00:00:00' WHERE id='c3'");
        jdbc.update("""
                INSERT INTO iqc_inspection_conversation_result
                    (id,task_id,conversation_id,created_time,execution_id,score_status,final_score,business_item_results_json,risk_level)
                VALUES ('old-own','own','conversation','2030-09-01 00:00:00','own-1','FINAL',5,'[{"status":"FAIL"}]','MEDIUM'),
                       ('legacy-own','own','legacy-conversation','2030-09-02 00:00:00',null,null,null,null,null)
                """);
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
                CREATE TABLE iqc_inspection_scheme_version (
                  id varchar(64) PRIMARY KEY, scheme_id varchar(64), version_no int, source_draft_revision int,
                  source_trial_task_id varchar(64), snapshot_json clob, content_hash varchar(64), archived boolean default false not null,
                  created_by varchar(128), created_time timestamp, updated_by varchar(128), updated_time timestamp)
                """);
        jdbc.update("""
                INSERT INTO iqc_inspection_scheme (id,name,status,created_by,owner_group_id)
                VALUES ('own-scheme','本人方案','DISABLED','alice','g1'),
                       ('team-scheme','同组方案','ACTIVE','bob','g1'),
                       ('foreign-scheme','外组方案','ACTIVE','eve','g2')
                """);
    }

    @BeforeEach
    void organizationScope() {
        // Rate limiting is out of scope: isolate it explicitly, never rely on unavailable sysadmin fail-open.
        when(rateLimiter.check(any())).thenReturn(new RateLimitDecision(true, 60, 0, 60, null, "isolated-test"));
        when(organization.getUserByUniqueId(anyString())).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("b", "Other", "other", "g2", "Group 2")));
        when(organization.getUserByUniqueId("alice")).thenReturn(Result.success(
                new IqcOrganizationClient.OrganizationUser("a", "Alice", "alice", "g1", "Group 1")));
    }

    @Test
    void anonymousCannotReadQueue() throws Exception {
        assertThat(get(null, "").statusCode()).isEqualTo(401);
        assertThat(business(null, "").statusCode()).isEqualTo(401);
    }

    @Test
    void signedIdentityScopesQueueBeforePagingAndIgnoresRequester() throws Exception {
        JsonNode alice = data(get(token("alice", List.of()), "?size=1"));
        assertThat(alice.path("total").asInt()).isEqualTo(2);
        assertThat(alice.path("records").get(0).path("reviewId").asText()).isEqualTo("r2");
        assertThat(alice.path("records").get(0).path("labelName").asText()).isEqualTo("是否有房");
        assertThat(alice.path("records").get(0).path("valueDescription").asText()).isEqualTo("客户拥有房产");
        JsonNode next = data(get(token("alice", List.of()), "?size=1&current=2"));
        assertThat(next.path("records").get(0).path("reviewId").asText()).isEqualTo("r1");

        JsonNode filtered = data(get(token("alice", List.of()), "?taskId=foreign"));
        assertThat(filtered.path("total").asInt()).isZero();

        JsonNode admin = data(get(token("admin", List.of("ADMIN")), ""));
        assertThat(admin.path("total").asInt()).isEqualTo(3);
        assertThat(admin.path("records").get(0).path("reviewId").asText()).isEqualTo("r3");
    }

    @Test
    void organizationLookupFailureCannotRevealTeamOrForeignTasks() throws Exception {
        when(organization.getUserByUniqueId("alice")).thenThrow(new IllegalStateException("organization unavailable"));
        JsonNode queue = data(get(token("alice", List.of()), ""));
        assertThat(queue.path("total").asInt()).isEqualTo(1);
        assertThat(queue.path("records").get(0).path("reviewId").asText()).isEqualTo("r1");
    }

    @Test
    void businessResultsScopeBeforePagingAndReturnOnlyCurrentAuthorizedRows() throws Exception {
        String alice = token("alice", List.of("IQC_VIEWER"));
        JsonNode first = data(business(alice, "?size=1"));
        assertThat(first.path("total").asInt()).isEqualTo(2);
        assertThat(first.path("records")).hasSize(1);
        JsonNode team = first.path("records").get(0);
        assertThat(team.path("id").asText()).isEqualTo("c2");
        assertThat(team.path("taskName").asText()).isEqualTo("同组任务");
        assertThat(team.path("sourceFileName").asText()).isEqualTo("隔离测试会话");
        assertThat(team.path("scoreStatus").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(team.path("finalScore").isNull()).isTrue();

        JsonNode second = data(business(alice, "?size=1&current=2"));
        assertThat(second.path("total").asInt()).isEqualTo(2);
        assertThat(second.path("records")).hasSize(1);
        JsonNode own = second.path("records").get(0);
        assertThat(own.path("id").asText()).isEqualTo("c1");
        assertThat(own.path("taskName").asText()).isEqualTo("本人任务");
        assertThat(own.path("finalScore").decimalValue()).isEqualByComparingTo("0");
        assertThat(own.path("failureCount").asInt()).isEqualTo(1);
        assertThat(data(business(alice, "?taskId=foreign")).path("records")).isEmpty();
        assertThat(data(business(alice, "?taskId=foreign")).path("total").asInt()).isZero();

        JsonNode admin = data(business(token("admin", List.of("ADMIN")), ""));
        assertThat(admin.path("total").asInt()).isEqualTo(3);
        assertThat(admin.path("records").get(0).path("id").asText()).isEqualTo("c3");
        assertThat(admin.path("records").get(0).path("finalScore").decimalValue()).isEqualByComparingTo("95.50");
    }

    @Test
    void businessFiltersCannotSelectOldMatchingScoresOrLegacyResults() throws Exception {
        String alice = token("alice", List.of());
        // The newer attempt scored zero; the old five-point result has a later timestamp but cannot match.
        assertThat(data(business(alice, "?minScore=1&maxScore=10")).path("total").asInt()).isZero();
        assertThat(data(business(alice, "?riskLevel=MEDIUM")).path("total").asInt()).isZero();
        JsonNode labelOnly = data(business(alice, "?scoreStatus=NOT_APPLICABLE"));
        assertThat(labelOnly.path("total").asInt()).isEqualTo(1);
        assertThat(labelOnly.path("records").get(0).path("finalScore").isNull()).isTrue();
    }

    @Test
    void businessOrganizationLookupFailureFallsBackToOwnTasksAndRejectsTamperedIdentity() throws Exception {
        when(organization.getUserByUniqueId("alice")).thenThrow(new IllegalStateException("organization unavailable"));
        String alice = token("alice", List.of());
        JsonNode own = data(business(alice, ""));
        assertThat(own.path("total").asInt()).isEqualTo(1);
        assertThat(own.path("records").get(0).path("taskId").asText()).isEqualTo("own");
        assertThat(data(business(alice, "?taskId=team")).path("total").asInt()).isZero();
        assertThat(business(alice + "tampered", "").statusCode()).isEqualTo(401);
    }

    private String token(String username, List<String> roles) {
        return tokens.issue(new InternalTokenRequest("iqc-platform", username, username, "iqc-platform",
                List.of(), roles, List.of(), 0, null, null, Map.of()));
    }

    @Test
    void signedHistoryChecksSchemeScopeEvenWhenThereAreNoPublishedVersions() throws Exception {
        String alice = token("alice", List.of());
        assertThat(request(null, "/api/iqc/schemes/own-scheme/versions").statusCode()).isEqualTo(401);
        assertThat(request(alice + "tampered", "/api/iqc/schemes/own-scheme/versions").statusCode()).isEqualTo(401);
        assertThat(data(request(alice, "/api/iqc/schemes/own-scheme/versions")).path("versions")).isEmpty();
        assertThat(data(request(alice, "/api/iqc/schemes/team-scheme/versions")).path("versions")).isEmpty();
        HttpResponse<String> foreign = request(alice, "/api/iqc/schemes/foreign-scheme/versions");
        assertDeniedHistory(foreign);
        assertThat(data(request(token("admin", List.of("ADMIN")),
                "/api/iqc/schemes/foreign-scheme/versions")).path("versions")).isEmpty();
        when(organization.getUserByUniqueId("alice")).thenThrow(new IllegalStateException("organization unavailable"));
        assertThat(data(request(alice, "/api/iqc/schemes/own-scheme/versions")).path("versions")).isEmpty();
        HttpResponse<String> team = request(alice, "/api/iqc/schemes/team-scheme/versions");
        assertDeniedHistory(team);
    }

    private void assertDeniedHistory(HttpResponse<String> response) throws Exception {
        // IQC business errors follow the existing HTTP-200 error envelope contract.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode error = mapper.readTree(response.body());
        assertThat(error.path("code").asText()).isEqualTo("IQC-1005");
        assertThat(error.has("data")).isFalse();
        assertThat(error.has("versions")).isFalse();
    }

    private HttpResponse<String> get(String token, String query) throws Exception {
        return request(token, "/api/iqc/quality-operations/label-reviews" + query);
    }

    private HttpResponse<String> business(String token, String query) throws Exception {
        return request(token, "/api/iqc/results/business" + query);
    }

    private HttpResponse<String> request(String token, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + path)).GET();
        if (token != null) request.header(InternalTokenConstants.HEADER, token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }
}
