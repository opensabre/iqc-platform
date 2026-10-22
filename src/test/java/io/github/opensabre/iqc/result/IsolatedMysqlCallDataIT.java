package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import io.github.opensabre.iqc.result.llm.gateway.*;
import java.sql.DriverManager;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Production mapper, cipher and transaction proxy on a verified temporary MySQL server. */
class IsolatedMysqlCallDataIT {
    private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String SCHEMA="iqc_call_data_"+Long.toHexString(System.nanoTime());
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static GatewayCallDataService service;
    private static LlmQualityProperties policy;
    @BeforeAll static void start()throws Exception{
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){sql.execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4");}
        var flyway=Flyway.configure().dataSource(SERVER+SCHEMA+OPTIONS,"root","").locations("classpath:db/migration/mysql").load();
        flyway.migrate();flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        context=new AnnotationConfigApplicationContext(DatabaseConfiguration.class);jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        service=context.getBean(GatewayCallDataService.class);policy=context.getBean(LlmQualityProperties.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
    }
    @AfterAll static void close()throws Exception{
        if(context!=null)context.close();IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){sql.execute("DROP DATABASE IF EXISTS "+SCHEMA);}
    }
    @BeforeEach void fixture(){jdbc.update("DELETE FROM iqc_model_gateway_call");policy.getCallData().setEnabled(true);policy.getCallData().setMaxBytesPerBody(65536);}
    private void insert(String id){jdbc.update("INSERT INTO iqc_model_gateway_call(call_id,model_key,status,reserved_at_utc,total_tokens,cost_status,calculated_cost) VALUES (?,?,'CONFIRMED',UTC_TIMESTAMP(3),9007199254740993,'CALCULATED',0.123456789012345678)",id,"model");}
    private java.util.Map<String,Object> row(String id){return jdbc.queryForMap("SELECT * FROM iqc_model_gateway_call WHERE call_id=?",id);}
    private void assertBody(String cipher,String expected)throws Exception{
        var json=new ObjectMapper().readTree(context.getBean(StringEncryptor.class).decrypt(cipher));assertThat(json.path("body").asText()).isEqualTo(expected);
        assertThat(json.path("schemaVersion").asText()).isEqualTo("iqc-model-call-data-v1");assertThat(json.path("modelKey").asText()).isEqualTo("model");
    }
    @Test void actualSchemaAndFirstWriteFencePreserveLedgerFacts()throws Exception{
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='iqc_model_gateway_call' AND column_name IN ('request_data_encrypted','response_data_encrypted') AND data_type='longtext'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='iqc_model_gateway_call' AND index_name='idx_gateway_call_data_expiry'",String.class)).isEqualTo("call_data_expires_at_utc");
        insert("first");var original=row("first");service.store(new GatewayCallService.Handle("first","wrong-model"),"wrong","wrong");assertThat(row("first")).isEqualTo(original);
        service.store(new GatewayCallService.Handle("first","model"),"处理好的会话","response");var archived=row("first");
        assertBody((String)archived.get("request_data_encrypted"),"处理好的会话");assertBody((String)archived.get("response_data_encrypted"),"response");
        assertThat(archived.get("call_data_status")).isEqualTo("CAPTURED");
        for(String field:original.keySet())if(!field.startsWith("call_data_")&&!field.endsWith("_data_encrypted"))assertThat(archived.get(field)).as(field).isEqualTo(original.get(field));
        service.store(new GatewayCallService.Handle("first","model"),"overwrite","overwrite");assertThat(row("first")).isEqualTo(archived);
    }
    @Test void competingArchivesNeverMixRequestAndResponse()throws Exception{
        insert("race");var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){
            java.util.List<Future<?>> futures=new java.util.ArrayList<>();
            for(String attempt:java.util.List.of("one","two"))futures.add(workers.submit(()->{ready.countDown();try{if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier");}catch(InterruptedException e){throw new IllegalStateException(e);}service.store(new GatewayCallService.Handle("race","model"),attempt,attempt);}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }finally{go.countDown();}
        var stored=row("race");var cipher=context.getBean(StringEncryptor.class);var json=new ObjectMapper();
        String request=json.readTree(cipher.decrypt((String)stored.get("request_data_encrypted"))).path("body").asText();assertThat(request).isIn("one","two");assertBody((String)stored.get("response_data_encrypted"),request);
    }
    @Test void archiveCommitsIndependentlyOfOuterRollback(){
        insert("independent");new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(tx->{service.store(new GatewayCallService.Handle("independent","model"),"request",null);tx.setRollbackOnly();});
        assertThat(row("independent").get("call_data_status")).isEqualTo("REQUEST_ONLY");
    }
    @Test void disabledCaptureStillPurgesAtMostTwoHundredWithoutChangingBilling(){
        for(int i=0;i<205;i++){insert("expired-"+i);jdbc.update("UPDATE iqc_model_gateway_call SET request_data_encrypted='encrypted-request',response_data_encrypted='encrypted-response',call_data_status='CAPTURED',call_data_recorded_at_utc=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,call_data_expires_at_utc=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE call_id=?","expired-"+i);}
        insert("future");service.store(new GatewayCallService.Handle("future","model"),"future",null);var future=row("future");
        policy.getCallData().setEnabled(false);service.purgeExpired();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_model_gateway_call WHERE call_data_status='EXPIRED'",Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_model_gateway_call WHERE call_data_status='EXPIRED' AND request_data_encrypted IS NULL AND response_data_encrypted IS NULL AND call_data_expires_at_utc IS NULL AND call_data_recorded_at_utc IS NOT NULL",Integer.class)).isEqualTo(200);
        service.purgeExpired();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_model_gateway_call WHERE call_data_status='EXPIRED'",Integer.class)).isEqualTo(205);assertThat(row("future")).isEqualTo(future);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_model_gateway_call WHERE status='CONFIRMED' AND total_tokens=9007199254740993 AND calculated_cost=0.123456789012345678 AND cost_status='CALCULATED'",Integer.class)).isEqualTo(206);
        service.purgeExpired();assertThat(row("future")).isEqualTo(future);
    }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class DatabaseConfiguration{
        @Bean DataSource dataSource(){return new DriverManagerDataSource(SERVER+SCHEMA+OPTIONS,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source)throws Exception{var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(GatewayCallMapper.class);var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);return factory.getObject();}
        @Bean GatewayCallMapper gatewayCallMapper(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(GatewayCallMapper.class);}
        @Bean LlmQualityProperties policy(){return new LlmQualityProperties();}
        @Bean StringEncryptor encryptor(){var cipher=new StandardPBEStringEncryptor();cipher.setPassword("isolated-test-only");cipher.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");cipher.setIvGenerator(new org.jasypt.iv.RandomIvGenerator());return cipher;}
        @Bean GatewayCallDataService service(GatewayCallMapper rows,LlmQualityProperties policy,ObjectProvider<StringEncryptor> cipher){return new GatewayCallDataService(rows,policy,cipher);}
    }
}
