package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.opensabre.iqc.conversation.*;
import io.github.opensabre.iqc.conversation.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.sql.DriverManager;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL original transcript import and frozen restoration; actor scope is a substitute. */
class IsolatedMysqlOfflineTranscriptIT {
    private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String SCHEMA="iqc_offline_transcript_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String URL=SERVER+SCHEMA+OPTIONS;
    private static AnnotationConfigApplicationContext context;
    private static boolean created;
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
    static class DatabaseConfiguration {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(URL,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(ConversationMapper.class);config.addMapper(ConversationMessageMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);return factory.getObject();
        }
        @Bean ConversationMapper conversations(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(ConversationMapper.class);}
        @Bean ConversationMessageMapper messages(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(ConversationMessageMapper.class);}
        @Bean IqcDataScope scope(){var scope=mock(IqcDataScope.class);when(scope.groupId()).thenReturn("team");when(scope.canView(any(),any())).thenReturn(true);return scope;}
        @Bean ConversationImportService imports(ConversationMapper conversations,ConversationMessageMapper messages,IqcDataScope scope,ObjectMapper json){return new ConversationImportService(conversations,messages,scope,json);}
        @Bean ProductionInputSnapshotService snapshots(ConversationMapper conversations,ConversationMessageMapper messages,IqcDataScope scope,ObjectMapper json){return new ProductionInputSnapshotService(conversations,messages,scope,json);}
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){
            sql.execute("CREATE DATABASE `"+SCHEMA+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        }
        var flyway=Flyway.configure().dataSource(URL,"root","").locations("classpath:db/migration/mysql").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
        context=new AnnotationConfigApplicationContext(DatabaseConfiguration.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(ConversationImportService.class)));
    }
    @AfterAll static void close() throws Exception {
        if(context!=null)context.close();
        if(created){IsolatedMysqlMigrationIT.verifyIsolatedServer();try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){sql.execute("DROP DATABASE `"+SCHEMA+"`");}}
    }
    @Test void realIngressPersistsReplaysAndRestoresFrozenAlignment() throws Exception {
        var imports=context.getBean(ConversationImportService.class);
        var json=context.getBean(ObjectMapper.class);
        var ingress=new ConversationApiIngestService(imports,mock(io.github.opensabre.governance.usage.UsageCounterRecorder.class));
        var request=new ApiConversationInput("external",null,"offline",null,List.of(new ApiConversationInput.Message(null,"user","hello",86400001L,86400500L,new BigDecimal("0.75"))));
        var conversation=ingress.ingest(request,null);
        assertNotNull(conversation.getId());
        var jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        assertEquals(86400001L,jdbc.queryForObject("SELECT start_ms FROM iqc_conversation_message WHERE conversation_id=?",Long.class,conversation.getId()));
        assertEquals(0,new BigDecimal("0.75").compareTo(jdbc.queryForObject("SELECT confidence FROM iqc_conversation_message WHERE conversation_id=?",BigDecimal.class,conversation.getId())));
        assertEquals(conversation.getId(),ingress.ingest(request,null).getId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_conversation_message",Integer.class));
        var changed=new ApiConversationInput("external",null,"offline",null,List.of(new ApiConversationInput.Message(null,"user","hello",86400001L,86400501L,new BigDecimal("0.75"))));
        assertThrows(ConversationReplayConflictException.class,()->ingress.ingest(changed,null));
        var rows=context.getBean(ConversationMapper.class);
        assertEquals(0,rows.bindAudio(conversation.getId(),"wrong-revision","a".repeat(64),16044,"audio/wav",86400500));
        assertEquals(1,rows.bindAudio(conversation.getId(),conversation.getSourceFingerprint(),"a".repeat(64),16044,"audio/wav",86400500));
        assertEquals(0,rows.bindAudio(conversation.getId(),conversation.getSourceFingerprint(),"b".repeat(64),16044,"audio/wav",86400500));
        var bound=rows.selectById(conversation.getId());
        assertEquals("a".repeat(64),bound.getAudioSha256());assertEquals(86400500L,bound.getAudioDurationMs());
        assertFalse(json.valueToTree(bound).has("audioSha256"));
        assertFalse(json.valueToTree(bound).has("audioSizeBytes"));
        var snapshots=context.getBean(ProductionInputSnapshotService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(snapshots));
        var root=json.createObjectNode();root.putObject("executionStrategy").put("mode","PIPELINE");root.set("productionInputs",snapshots.capture(List.of(conversation.getId())));
        assertEquals(ProductionInputSnapshotService.AUDIO_SCHEMA,root.path("productionInputs").path("schemaVersion").asText());
        var task=new InspectionTask();task.setId("task");task.setTaskType("PIPELINE");task.setTotalMessages(1);task.setConversationIdsJson(json.writeValueAsString(List.of(conversation.getId())));task.setRuleSnapshotJson(root.toString());
        jdbc.update("UPDATE iqc_conversation_message SET end_ms=? WHERE conversation_id=?",86400501L,conversation.getId());
        jdbc.update("UPDATE iqc_conversation SET audio_sha256=? WHERE id=?","b".repeat(64),conversation.getId());
        assertEquals("a".repeat(64),snapshots.prepare(task,root).audio(conversation.getId()).sha256());
        assertEquals(86400500L,snapshots.prepare(task,root).audio(conversation.getId()).durationMs());
        var restored=snapshots.prepare(task,root).messages().get(conversation.getId()).getFirst();
        assertEquals(86400500L,restored.getEndMs());
        assertEquals(0,new BigDecimal("0.75").compareTo(restored.getConfidence()));
        assertThrows(ConversationReplayConflictException.class,()->ingress.ingest(request,null));
        var scope=context.getBean(IqcDataScope.class);when(scope.canView(any(),any())).thenReturn(false);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->snapshots.capture(List.of(conversation.getId())));
    }
}
