package io.github.opensabre.iqc.quality;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.BusinessReviewQueueItem;
import io.github.opensabre.iqc.label.model.LabelReviewQueueItem;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BusinessReviewQueueSqlTest {
    @Test
    void scopePrecedesPagingAndUsesTaskOwnershipInsteadOfRequester() throws Exception {
        var source = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:review_queue;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE iqc_inspection_task (id varchar(64) PRIMARY KEY, name varchar(100), status varchar(32), created_by varchar(64), owner_group_id varchar(64))");
            sql.execute("CREATE TABLE iqc_inspection_conversation_result (id varchar(64) PRIMARY KEY, task_id varchar(64), conversation_id varchar(64), created_time timestamp)");
            sql.execute("CREATE TABLE iqc_task_execution (id varchar(64) PRIMARY KEY, task_id varchar(64), attempt_no int)");
            sql.execute("CREATE TABLE iqc_inspection_label_result (id varchar(64) PRIMARY KEY, conversation_result_id varchar(64), label_id varchar(64), label_version_no int, value_code varchar(64))");
            sql.execute("CREATE TABLE iqc_result_review (id varchar(64) PRIMARY KEY, target_type varchar(32), business_result_id varchar(64), label_result_id varchar(64), review_revision int, status varchar(32), request_comment varchar(100), created_by varchar(64), created_time timestamp)");
            sql.execute("INSERT INTO iqc_inspection_task VALUES ('own','本人任务','SUCCEEDED','alice','g1'),('team','同组任务','SUCCEEDED','bob','g1'),('foreign','其他组','SUCCEEDED','eve','g2')");
            sql.execute("INSERT INTO iqc_inspection_conversation_result VALUES ('c1','own','conversation','2026-09-01 00:00:00'),('c2','team','conversation','2026-09-01 00:00:00'),('c3','foreign','conversation','2026-09-01 00:00:00'),('c4','own','conversation','2026-09-01 00:00:00')");
            sql.execute("ALTER TABLE iqc_inspection_conversation_result ADD execution_id varchar(64)");
            sql.execute("INSERT INTO iqc_inspection_label_result VALUES ('l1','c1','house',2,'owned'),('l2','c2','house',2,'owned'),('l3','c3','house',2,'owned'),('l4','c4','house',2,'owned')");
            sql.execute("INSERT INTO iqc_result_review VALUES ('r1','BUSINESS','c1',NULL,1,'PENDING','本人原结果','other','2026-09-01 00:00:00'),('r2','BUSINESS','c2',NULL,1,'PENDING','同组结果','other','2026-09-02 00:00:00'),('r3','BUSINESS','c3',NULL,1,'PENDING','申请人不是权限依据','alice','2026-09-03 00:00:00'),('legacy','MESSAGE','c4',NULL,1,'PENDING','排除消息记录','alice','2026-09-04 00:00:00')");
            sql.execute("INSERT INTO iqc_result_review VALUES ('lr1','LABEL',NULL,'l1',1,'PENDING','本人旧结果','other','2026-09-05 00:00:00'),('lr2','LABEL',NULL,'l2',1,'PENDING','同组结果','other','2026-09-06 00:00:00'),('lr3','LABEL',NULL,'l3',1,'PENDING','外组申请人伪装','alice','2026-09-07 00:00:00'),('lr4','LABEL',NULL,'l4',1,'COMPLETED','本人当前结果','other','2026-09-08 00:00:00')");
            sql.execute("INSERT INTO iqc_task_execution VALUES ('e1','own',1),('e2','own',2)");
            sql.execute("UPDATE iqc_inspection_conversation_result SET execution_id = 'e1', created_time = '2030-01-01 00:00:00' WHERE id = 'c1'");
            sql.execute("UPDATE iqc_inspection_conversation_result SET execution_id = 'e2' WHERE id = 'c4'");
        }
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        var plugin = new MybatisPlusInterceptor(); plugin.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        config.addInterceptor(plugin); config.addMapper(ResultReviewMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(config);
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(ResultReviewMapper.class);
            var own = mapper.selectBusinessQueue(new Page<BusinessReviewQueueItem>(1, 1), "PENDING", null, false, "alice", null);
            assertThat(own.getTotal()).isEqualTo(1); assertThat(own.getRecords().getFirst().reviewId()).isEqualTo("r1");
            assertThat(own.getRecords().getFirst().currentResult()).isFalse();
            var team = mapper.selectBusinessQueue(new Page<BusinessReviewQueueItem>(1, 1), "PENDING", null, false, "alice", "g1");
            assertThat(team.getTotal()).isEqualTo(2); assertThat(team.getRecords().getFirst().reviewId()).isEqualTo("r2");
            assertThat(team.getRecords().getFirst().currentResult()).isTrue();
            var page2 = mapper.selectBusinessQueue(new Page<BusinessReviewQueueItem>(2, 1), "PENDING", null, false, "alice", "g1");
            assertThat(page2.getRecords().getFirst().reviewId()).isEqualTo("r1");
            assertThat(mapper.selectBusinessQueue(new Page<BusinessReviewQueueItem>(1, 20), null, null, true, "alice", null).getTotal()).isEqualTo(3);
            assertThat(mapper.selectBusinessQueue(new Page<BusinessReviewQueueItem>(1, 20), "PENDING", "foreign", false, "alice", "g1").getTotal()).isZero();
            assertThat(mapper.selectBusinessQueue(new Page<BusinessReviewQueueItem>(1, 20), "COMPLETED", null, true, "alice", null).getTotal()).isZero();
            var labelOwn = mapper.selectLabelQueue(new Page<LabelReviewQueueItem>(1, 1), "PENDING", null, false, "alice", null);
            assertThat(labelOwn.getTotal()).isEqualTo(1);
            assertThat(labelOwn.getRecords().getFirst().getReviewId()).isEqualTo("lr1");
            assertThat(labelOwn.getRecords().getFirst().isCurrentResult()).isFalse();
            assertThat(labelOwn.getRecords().getFirst().getLabelVersionNo()).isEqualTo(2);
            var labelTeam = mapper.selectLabelQueue(new Page<LabelReviewQueueItem>(1, 1), "PENDING", null, false, "alice", "g1");
            assertThat(labelTeam.getTotal()).isEqualTo(2);
            assertThat(labelTeam.getRecords().getFirst().getReviewId()).isEqualTo("lr2");
            assertThat(labelTeam.getRecords().getFirst().isCurrentResult()).isTrue();
            assertThat(mapper.selectLabelQueue(new Page<LabelReviewQueueItem>(1, 20), null, null, true, "alice", null).getTotal()).isEqualTo(4);
            assertThat(mapper.selectLabelQueue(new Page<LabelReviewQueueItem>(1, 20), "PENDING", "foreign", false, "alice", "g1").getTotal()).isZero();
        }
    }
}
