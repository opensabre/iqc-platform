package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.ProductionInputSnapshotService;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real publication/input freezing/SQL at the inner-input versus outer-task byte boundary. */
class IsolatedMysqlRuleSnapshotEnvelopeIT {
    private static final int MESSAGES = 512;
    private static final int LIMIT = 16 * 1024 * 1024;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate db;
    private static ObjectMapper json;

    @BeforeAll static void start() throws Exception {
        // Reuse the measured release, Gold and independent review fixture rather than inserting a release.
        IsolatedMysqlMeasuredPublicationIT.start();
        new IsolatedMysqlMeasuredPublicationIT().realMeasuredPublicationMaterializesAndExecutesFrozenProductionInput();
        context = new AnnotationConfigApplicationContext(IsolatedMysqlMeasuredPublicationIT.Config.class);
        db = new JdbcTemplate(context.getBean(DataSource.class));
        json = context.getBean(ObjectMapper.class);
        org.mockito.Mockito.when(context.getBean(io.github.opensabre.iqc.shared.IqcDataScope.class).owner()).thenReturn("annotator");
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(
                Map.of("user_name", "annotator", "user_id", "user-annotator"));
    }

    @AfterAll static void close() {
        if (context != null) context.close();
        IsolatedMysqlMeasuredPublicationIT.close();
    }

    @Test void acceptedInputNearSixteenMiBStillPersistsItsWholeTaskEnvelope() throws Exception {
        String conversation = "rule-snapshot-boundary";
        db.update("INSERT INTO iqc_conversation(id,source_file_name,status,message_count,created_by,owner_group_id,source_fingerprint) "
                + "VALUES (?,'processed','IMPORTED',?,'annotator','group',SHA2(?,256))", conversation, MESSAGES, conversation);
        var rows = new ArrayList<Object[]>();
        for (int i = 1; i <= MESSAGES; i++) rows.add(new Object[]{"boundary-msg-" + i, conversation, i, i});
        db.batchUpdate("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) "
                + "VALUES (?,?,?,'agent','x','00:00:00','processed',?)", rows);

        var inputs = context.getBean(ProductionInputSnapshotService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(inputs));
        // Calibrate with the actual serializer, then keep every individual message within its original TEXT limit.
        var tiny = inputs.capture(List.of(conversation));
        int overhead = bytes(tiny.toString()) - MESSAGES;
        int target = LIMIT - 16;
        int textBytes = target - overhead;
        int each = textBytes / MESSAGES, extra = textBytes % MESSAGES;
        assertTrue(each > 0 && each + extra <= 65_535);
        db.update("UPDATE iqc_conversation_message SET content=REPEAT('x',?) WHERE conversation_id=?", each, conversation);
        db.update("UPDATE iqc_conversation_message SET content=REPEAT('x',?) WHERE id=?", each + extra, "boundary-msg-" + MESSAGES);
        var accepted = inputs.capture(List.of(conversation));
        assertEquals(target, bytes(accepted.toString()));
        assertEquals(MESSAGES, accepted.path("conversations").get(0).path("messages").size());
        assertTrue(db.queryForObject("SELECT MAX(OCTET_LENGTH(content)) FROM iqc_conversation_message WHERE conversation_id=?", Integer.class, conversation) <= 65_535);

        String pipeline = db.queryForObject("SELECT DISTINCT v.pipeline_id FROM iqc_pipeline_asset_version v JOIN iqc_pipeline_asset p "
                + "ON p.id=v.pipeline_id WHERE p.name='Measured publication' AND v.version_no=1", String.class);
        var prepared = context.getBean(PipelineAssetService.class).publishedReleaseForTask(pipeline, 1);
        var tasks = context.getBean(InspectionTaskService.class);
        int before = taskCount();
        // This is the original transactional task INSERT, including the outer pipeline/fingerprint envelope.
        var created = tasks.createUsingPipelineSnapshot(prepared, "Input envelope boundary", List.of(conversation), 1,
                "rule_snapshot_boundary_case_01");
        assertEquals(before + 1, taskCount());
        var stored = context.getBean(SqlSessionTemplate.class).getMapper(InspectionTaskMapper.class).selectById(created.getId());
        assertEquals(created.getRuleSnapshotJson(), stored.getRuleSnapshotJson());
        assertTrue(bytes(stored.getRuleSnapshotJson()) > LIMIT - 1);
        assertEquals(accepted, json.readTree(stored.getRuleSnapshotJson()).path("productionInputs"));
        assertEquals(MESSAGES, stored.getTotalMessages());

        // Larger SQL storage must not relax the existing input guard or create a task on rejection.
        db.update("UPDATE iqc_conversation_message SET content=CONCAT(content,REPEAT('x',32)) WHERE id=?", "boundary-msg-" + MESSAGES);
        var failure = assertThrows(io.github.opensabre.iqc.governance.IqcException.class, () ->
                tasks.createUsingPipelineSnapshot(prepared, "Oversized input", List.of(conversation), 1,
                        "rule_snapshot_boundary_case_02"));
        assertTrue(failure.getMessage().contains("16MiB"));
        assertEquals(before + 1, taskCount());
        System.out.println("RULE_SNAPSHOT_BOUNDARY inputBytes=" + target + " taskBytes=" + bytes(stored.getRuleSnapshotJson())
                + " messages=" + MESSAGES + " oversizedRejected=true");
    }

    @Test void upgradePreservesHistoricalSnapshotsAndIsRepeatable() throws Exception {
        String url = IsolatedMysqlTaskMetricsIT.createSchema("iqc_rule_envelope_upgrade_");
        IsolatedMysqlTaskMetricsIT.flyway(url, "1.1.78").migrate();
        var upgradeDb = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "root", ""));
        upgradeDb.update("INSERT INTO iqc_inspection_task(id,name,status,rule_snapshot_json) VALUES ('history','history','CREATED',?)",
                "{\"evidence\":\"处理好的会话\"}");
        upgradeDb.update("INSERT INTO iqc_inspection_task(id,name,status) VALUES ('null-history','history','CREATED')");
        var latest = IsolatedMysqlTaskMetricsIT.flyway(url, null);
        assertEquals(1, latest.migrate().migrationsExecuted);
        latest.validate();
        assertEquals(0, latest.migrate().migrationsExecuted);
        assertEquals("{\"evidence\":\"处理好的会话\"}", upgradeDb.queryForObject(
                "SELECT rule_snapshot_json FROM iqc_inspection_task WHERE id='history'", String.class));
        assertTrue(Boolean.TRUE.equals(upgradeDb.queryForObject(
                "SELECT rule_snapshot_json IS NULL FROM iqc_inspection_task WHERE id='null-history'", Boolean.class)));
        assertEquals("longtext", upgradeDb.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='iqc_inspection_task' AND COLUMN_NAME='rule_snapshot_json'", String.class));
    }

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static int taskCount() { return db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task", Integer.class); }
}
