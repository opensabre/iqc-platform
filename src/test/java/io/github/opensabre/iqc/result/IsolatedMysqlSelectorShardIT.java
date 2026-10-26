package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.selector.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** New isolated schema, real bounded keyset/targeted SQL and transactions; never shared credentials. */
class IsolatedMysqlSelectorShardIT {
    static String url;static AnnotationConfigApplicationContext context;static JdbcTemplate jdbc;
    static ConversationSelectionService service;static IqcDataScope scope;
    static final Instant ANCHOR=Instant.parse("2026-10-01T00:00:00.123Z");
    @BeforeAll static void start() throws Exception {
        url=IsolatedMysqlTaskMetricsIT.createSchema("iqc_selector_shard_");IsolatedMysqlTaskMetricsIT.flyway(url,null).migrate();
        context=new AnnotationConfigApplicationContext(Database.class);jdbc=new JdbcTemplate(context.getBean(DataSource.class));service=context.getBean(ConversationSelectionService.class);scope=context.getBean(IqcDataScope.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @BeforeEach void clear(){jdbc.update("DELETE FROM iqc_conversation");when(scope.canViewAll()).thenReturn(false);when(scope.owner()).thenReturn("owner");when(scope.groupId()).thenReturn("group");}
    SelectorDefinition definition(SelectorDefinition.Condition condition){return new SelectorDefinition(SelectorDefinition.SCHEMA,condition,null,List.of(),List.of(),null,null,1000);}
    void seed(String id,String owner,String group,Instant created,String channel){jdbc.update("INSERT INTO iqc_conversation(id,source_file_name,status,message_count,created_by,owner_group_id,created_time,channel,source_fingerprint) VALUES (?,'processed conversation','IMPORTED',1,?,?,?,?,SHA2(?,256))",id,owner,group,created==null?null:Date.from(created),channel,id);}
    @Test void exhaustiveScanCoversMoreThanLegacyPoolLimitAndAdvancesAcrossZeroMatchPages(){
        var rows=new ArrayList<Object[]>();for(int i=0;i<20137;i++)rows.add(new Object[]{String.format("c%06d",i),Date.from(ANCHOR.minusSeconds(1)),i<20000?"SKIP":"MATCH",String.format("c%06d",i)});
        jdbc.batchUpdate("INSERT INTO iqc_conversation(id,source_file_name,status,message_count,created_by,owner_group_id,created_time,channel,source_fingerprint) VALUES (?,'processed conversation','IMPORTED',1,'owner','group',?,?,SHA2(?,256))",rows);
        seed("future","owner","group",ANCHOR.plusSeconds(1),"MATCH");seed("foreign","foreign","other",ANCHOR.minusSeconds(1),"MATCH");
        var definition=definition(new SelectorDefinition.Condition(null,null,"channel",SelectorDefinition.Operator.EQ,new ObjectMapper().valueToTree("MATCH")));
        assertThatThrownBy(()->service.materializeAt(definition,ANCHOR)).hasMessageContaining("20000");
        String after=null;int scanned=0,pages=0;var selected=new LinkedHashSet<String>();
        while(true){
            var page=service.scanAllPageAt(definition,ANCHOR,after,1000);pages++;assertThat(pages).isLessThan(30);
            assertThat(page.afterId()).isEqualTo(after);assertThat(page.anchor()).isEqualTo(ANCHOR);assertThat(page.scannedCount()).isBetween(1,1000);
            assertThat(page.selection().candidateCount()).isEqualTo(page.scannedCount());assertThat(page.selection().limited()).isFalse();
            if(pages<=20)assertThat(page.selection().selectedConversationIds()).isEmpty();
            scanned+=page.scannedCount();for(String id:page.selection().selectedConversationIds())assertThat(selected.add(id)).isTrue();
            after=page.nextAfterId();if(!page.hasMore())break;
        }
        assertThat(scanned).isEqualTo(20137);assertThat(pages).isEqualTo(21);assertThat(selected).hasSize(137).doesNotContain("future","foreign");
        assertThat(after).isEqualTo("c020136");var empty=service.scanAllPageAt(definition,ANCHOR,after,1000);assertThat(empty.selection()).isNull();assertThat(empty.scannedCount()).isZero();assertThat(empty.hasMore()).isFalse();assertThat(empty.nextAfterId()).isEqualTo(after);
        var targeted=service.materializeTargetsAt(definition,ANCHOR,List.copyOf(selected));assertThat(targeted.selectedConversationIds()).containsExactlyElementsOf(selected);
    }
    @Test void scopeAndAnchorRetainOwnerOrGroupAndAllowExplicitGlobalScope(){
        seed("a","owner","other",ANCHOR,"MATCH");seed("b","other","group",ANCHOR,"MATCH");seed("c","other","other",ANCHOR,"MATCH");seed("d","owner","group",ANCHOR.plusSeconds(1),"MATCH");
        var d=definition(null);assertThat(service.scanAllPageAt(d,ANCHOR.plusNanos(999999),null,1000).selection().selectedConversationIds()).containsExactly("a","b");
        when(scope.groupId()).thenReturn(null);assertThat(service.scanAllPageAt(d,ANCHOR,null,1000).selection().selectedConversationIds()).containsExactly("a");
        when(scope.canViewAll()).thenReturn(true);assertThat(service.scanAllPageAt(d,ANCHOR,null,1000).selection().selectedConversationIds()).containsExactly("a","b","c");
    }
    /** Existing source timestamps have second precision: this records the unresolved same-second fence. */
    @Test void sourceTimestampPrecisionCannotProveSameSecondExclusion(){
        assertThat(jdbc.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='iqc_conversation' AND COLUMN_NAME='created_time'",Integer.class)).isZero();
        seed("same-second-future","owner","group",ANCHOR.plusMillis(1),"MATCH");
        // The database cannot distinguish this row from a pre-anchor row within the same second.
        assertThat(service.scanAllPageAt(definition(null),ANCHOR,null,1000).selection().selectedConversationIds()).containsExactly("same-second-future");
    }
    @Test void undatedEligibleSourceRejectsButUnauthorizedUndatedRowDoesNotBlock(){
        seed("a","owner","group",null,"MATCH");assertThatThrownBy(()->service.scanAllPageAt(definition(null),ANCHOR,null,1000)).hasMessageContaining("创建时间");
        jdbc.update("UPDATE iqc_conversation SET created_by='other',owner_group_id='other' WHERE id='a'");seed("b","owner","group",ANCHOR,"MATCH");
        assertThat(service.scanAllPageAt(definition(null),ANCHOR,null,1000).selection().selectedConversationIds()).containsExactly("b");
    }
    @Test void rejectsOtherSamplingModesAndInvalidCursorAnchorOrSize(){
        for(var mode:SelectorDefinition.SamplingMode.values())if(mode!=SelectorDefinition.SamplingMode.ALL){
            var sample=new SelectorDefinition.Sampling(mode,mode==SelectorDefinition.SamplingMode.RATIO?null:1,mode==SelectorDefinition.SamplingMode.RATIO?java.math.BigDecimal.ONE:null,"seed");
            var d=new SelectorDefinition(SelectorDefinition.SCHEMA,null,null,List.of(),List.of(),null,sample,1000);
            assertThatThrownBy(()->service.scanAllPageAt(d,ANCHOR,null,1000)).hasMessageContaining("仅支持ALL");
        }
        for(int size:new int[]{0,-1,1001})assertThatThrownBy(()->service.scanAllPageAt(definition(null),ANCHOR,null,size)).hasMessageContaining("无效");
        assertThatThrownBy(()->service.scanAllPageAt(definition(null),null,null,1)).hasMessageContaining("无效");
        assertThatThrownBy(()->service.scanAllPageAt(definition(null),ANCHOR,"x' OR 1=1",1)).hasMessageContaining("无效");
    }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Database {
        @Bean DataSource source(){return new DriverManagerDataSource(url,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);c.addMapper(ConversationMapper.class);var f=new MybatisSqlSessionFactoryBean();f.setDataSource(source);f.setConfiguration(c);return f.getObject();}
        @Bean IqcDataScope scope(){return mock(IqcDataScope.class);}
        @Bean ConversationSelectionService selections(SqlSessionFactory sessions,IqcDataScope scope){return new ConversationSelectionService(new SqlSessionTemplate(sessions).getMapper(ConversationMapper.class),mock(ConversationInspectionResultMapper.class),mock(InspectionLabelResultMapper.class),scope,new ObjectMapper());}
    }
}
