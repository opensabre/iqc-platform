package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real isolated migration and concurrent SQL constraint checks; no shared application database. */
class IsolatedMysqlBulkFieldsIT {
    JdbcTemplate database(String url){return new JdbcTemplate(new DriverManagerDataSource(url,"root",""));}
    void insert(JdbcTemplate db,String id,String parent,Integer shard){
        db.update("INSERT INTO iqc_inspection_task(id,name,status,bulk_parent_task_id,bulk_shard_no) VALUES (?,'processed conversation batch','CREATED',?,?)",id,parent,shard);
    }
    void contract(JdbcTemplate db){
        assertThat(db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='iqc_inspection_task' AND column_name IN ('bulk_parent_task_id','bulk_shard_no','bulk_progress_json') AND is_nullable='YES' ORDER BY ordinal_position",String.class))
            .containsExactly("bulk_parent_task_id","bulk_shard_no","bulk_progress_json");
        assertThat(db.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='iqc_inspection_task' AND index_name='uk_iqc_task_bulk_shard' AND non_unique=0 ORDER BY seq_in_index",String.class))
            .containsExactly("bulk_parent_task_id","bulk_shard_no");
    }
    @Test void freshSchemaMigratesValidatesAndRepeatsWithoutChanges() throws Exception {
        String url=IsolatedMysqlTaskMetricsIT.createSchema("iqc_bulk_new_");var migration=IsolatedMysqlTaskMetricsIT.flyway(url,"1.1.77");
        assertThat(migration.migrate().migrationsExecuted).isPositive();migration.validate();assertThat(migration.migrate().migrationsExecuted).isZero();contract(database(url));
    }
    @Test void upgradePreservesHistoryAndDatabaseSerializesDuplicateShardWriters() throws Exception {
        String url=IsolatedMysqlTaskMetricsIT.createSchema("iqc_bulk_upgrade_");IsolatedMysqlTaskMetricsIT.flyway(url,"1.1.76").migrate();var db=database(url);
        db.update("INSERT INTO iqc_inspection_task(id,name,status) VALUES ('history','old task','SUCCEEDED')");
        var upgrade=IsolatedMysqlTaskMetricsIT.flyway(url,"1.1.77");assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);upgrade.validate();assertThat(upgrade.migrate().migrationsExecuted).isZero();contract(db);
        assertThat(db.queryForObject("SELECT status='SUCCEEDED' AND bulk_parent_task_id IS NULL AND bulk_shard_no IS NULL AND bulk_progress_json IS NULL FROM iqc_inspection_task WHERE id='history'",Boolean.class)).isTrue();
        insert(db,"ordinary-a",null,null);insert(db,"ordinary-b",null,null);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->attempt(db,"child-a",ready,start));var second=pool.submit(()->attempt(db,"child-b",ready,start));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(java.util.List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("INSERTED","DUPLICATE");
        } finally {start.countDown();pool.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE bulk_parent_task_id='parent' AND bulk_shard_no=0",Integer.class)).isEqualTo(1);
        insert(db,"next","parent",1);insert(db,"other-parent","different-parent",0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task",Integer.class)).isEqualTo(6);
    }
    String attempt(JdbcTemplate db,String id,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("writer barrier timeout");
        try {insert(db,id,"parent",0);return "INSERTED";}catch(org.springframework.dao.DuplicateKeyException expected){return "DUPLICATE";}
    }
    @Test void populatedInternalFieldsNeverEnterDefaultTaskJson() throws Exception {
        var task=new InspectionTask();task.setName("visible");task.setBulkParentTaskId("private-parent");task.setBulkShardNo(9);task.setBulkProgressJson("private-checkpoint");
        var node=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(task));assertThat(node.path("name").asText()).isEqualTo("visible");
        assertThat(node.has("bulkParentTaskId")).isFalse();assertThat(node.has("bulkShardNo")).isFalse();assertThat(node.has("bulkProgressJson")).isFalse();
    }
}
