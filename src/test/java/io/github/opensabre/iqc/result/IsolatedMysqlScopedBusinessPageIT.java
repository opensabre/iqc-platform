package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Real SQL window ranking, scoped count and pagination in a fresh isolated schema. */
class IsolatedMysqlScopedBusinessPageIT {
    static JdbcTemplate db;static ConversationInspectionResultMapper mapper;
    @BeforeAll static void start() throws Exception {
        String url=IsolatedMysqlTaskMetricsIT.createSchema("iqc_scoped_page_");IsolatedMysqlTaskMetricsIT.flyway(url,null).migrate();
        var source=new DriverManagerDataSource(url,"root","");db=new JdbcTemplate(source);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(ConversationInspectionResultMapper.class);
        var plugin=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();plugin.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));config.addInterceptor(plugin);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ConversationInspectionResultMapper.class);
    }
    @BeforeEach void seed(){
        db.update("DELETE FROM iqc_inspection_conversation_result");db.update("DELETE FROM iqc_task_execution");db.update("DELETE FROM iqc_inspection_task");
        task("owned","owner","other","PIPELINE","parent");task("grouped","other","group","PIPELINE","parent");task("foreign","other","foreign","PIPELINE","parent");task("evaluation","owner","group","PIPELINE_EVALUATION","parent");task("different","owner","group","PIPELINE","elsewhere");
        result("old","owned",1,"HIGH",20,"2030-01-01 00:00:00");result("new","owned",2,"LOW",80,"2020-01-01 00:00:00");
        result("group-result","grouped",1,"HIGH",30,"2020-01-01 00:00:00");result("foreign-result","foreign",1,"HIGH",10,"2020-01-01 00:00:00");result("eval-result","evaluation",1,"HIGH",10,"2020-01-01 00:00:00");result("different-result","different",1,"HIGH",10,"2020-01-01 00:00:00");
    }
    void task(String id,String owner,String group,String type,String parent){db.update("INSERT INTO iqc_inspection_task(id,name,status,task_type,created_by,owner_group_id,bulk_parent_task_id) VALUES (?,?,'SUCCEEDED',?,?,?,?)",id,id,type,owner,group,parent);}
    void result(String id,String task,int attempt,String risk,int score,String time){
        db.update("INSERT INTO iqc_task_execution(id,task_id,attempt_no,status) VALUES (?,?,?,'SUCCEEDED')",id,task,attempt);
        db.update("INSERT INTO iqc_inspection_conversation_result(id,task_id,execution_id,conversation_id,result_status,reason,score_status,risk_level,final_score,created_time) VALUES (?,?,?,'conversation','PASS','processed','FINAL',?,?,?)",id,task,id,risk,score,time);
    }
    com.baomidou.mybatisplus.core.metadata.IPage<ConversationInspectionResult> page(long number,int size,boolean all,String group,String risk,java.math.BigDecimal min){return mapper.selectScopedBusinessPage(new Page<>(number,size),null,null,"parent",all,"owner",group,null,risk,min,null);}
    @Test void scopedCountAndPagesRetainOwnerOrGroupAndExcludeOtherParentsAndEvaluation(){
        var first=page(1,1,false,"group",null,null);var second=page(2,1,false,"group",null,null);assertThat(first.getTotal()).isEqualTo(2);assertThat(second.getTotal()).isEqualTo(2);
        assertThat(java.util.stream.Stream.concat(first.getRecords().stream(),second.getRecords().stream()).map(ConversationInspectionResult::getId).toList()).containsExactlyInAnyOrder("new","group-result");
        assertThat(page(3,1,false,"group",null,null).getRecords()).isEmpty();
    }
    @Test void rankBeforeFilteringNeverFallsBackToOlderMatchingResult(){
        assertThat(page(1,20,false,null,"HIGH",null).getTotal()).isZero();
        var current=page(1,20,false,null,"LOW",java.math.BigDecimal.valueOf(50));assertThat(current.getTotal()).isEqualTo(1);assertThat(current.getRecords()).extracting(ConversationInspectionResult::getId).containsExactly("new");
    }
    @Test void globalScopeStillExcludesEvaluationAndRespectsParent(){assertThat(page(1,20,true,null,null,null).getRecords()).extracting(ConversationInspectionResult::getId).containsExactlyInAnyOrder("new","group-result","foreign-result");}
    @Test void explicitLegacyIdsUseSameRankingAndRequireNonemptyScope(){
        assertThat(mapper.selectBusinessPage(new Page<>(1,20),java.util.List.of("owned"),null,null,null,null).getRecords()).extracting(ConversationInspectionResult::getId).containsExactly("new");
        assertThatThrownBy(()->mapper.selectBusinessPage(new Page<>(1,20),java.util.List.of(),null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
