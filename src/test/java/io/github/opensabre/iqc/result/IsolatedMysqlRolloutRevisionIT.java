package io.github.opensabre.iqc.result;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Formal migrated rollout table constraints on a verified temporary local MySQL server; no application credentials. */
class IsolatedMysqlRolloutRevisionIT {
 private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
 private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
 private static final String SCHEMA="iqc_rollout_"+Long.toHexString(System.nanoTime());
 private static boolean created;
 private static Connection connect()throws SQLException{return DriverManager.getConnection(SERVER+SCHEMA+OPTIONS,"root","");}
 @BeforeAll static void start()throws Exception{
  IsolatedMysqlMigrationIT.verifyIsolatedServer();
  try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){sql.execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4");created=true;}
  var flyway=Flyway.configure().dataSource(SERVER+SCHEMA+OPTIONS,"root","").locations("classpath:db/migration/mysql").cleanDisabled(true).load();
  flyway.migrate();flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
 }
 @AfterAll static void stop()throws Exception{if(created){IsolatedMysqlMigrationIT.verifyIsolatedServer();try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){sql.execute("DROP DATABASE "+SCHEMA);}}}
 private static void insert(Connection db,String id,String pipeline,int revision,String snapshot)throws SQLException{
  try(var sql=db.prepareStatement("INSERT INTO iqc_pipeline_rollout_revision(id,pipeline_id,revision_no,snapshot_json,content_hash) VALUES(?,?,?,?,?)")){sql.setString(1,id);sql.setString(2,pipeline);sql.setInt(3,revision);sql.setString(4,snapshot);sql.setString(5,"a".repeat(64));sql.executeUpdate();}
 }
 @Test void updateAndDeleteTriggersRejectEvenNoopAndPreserveOriginal()throws Exception{
  try(var db=connect();var sql=db.createStatement()){
   insert(db,"immutable","immutable-pipeline",1,"{\"original\":true}");
   for(String mutation:List.of("UPDATE iqc_pipeline_rollout_revision SET snapshot_json='{}' WHERE id='immutable'","UPDATE iqc_pipeline_rollout_revision SET revision_no=revision_no WHERE id='immutable'","DELETE FROM iqc_pipeline_rollout_revision WHERE id='immutable'")){
    assertThatThrownBy(()->sql.executeUpdate(mutation)).isInstanceOf(SQLException.class).satisfies(error->assertThat(((SQLException)error).getSQLState()).isEqualTo("45000"));
   }
   try(var result=sql.executeQuery("SELECT snapshot_json,revision_no,content_hash FROM iqc_pipeline_rollout_revision WHERE id='immutable'")){assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("{\"original\":true}");assertThat(result.getInt(2)).isEqualTo(1);assertThat(result.getString(3)).isEqualTo("a".repeat(64));assertThat(result.next()).isFalse();}
  }
 }
 @Test void revisionUniquenessIsScopedToExactPipelineAndRejectsDuplicate()throws Exception{
  try(var db=connect()){
   insert(db,"unique-1","case-sensitive",1,"{}");
   assertThatThrownBy(()->insert(db,"unique-2","case-sensitive",1,"{}")).isInstanceOf(SQLException.class).satisfies(error->assertThat(((SQLException)error).getErrorCode()).isEqualTo(1062));
   insert(db,"unique-3","case-sensitive",2,"{}");insert(db,"unique-4","CASE-SENSITIVE",1,"{}");
  }
 }
 @Test void positiveRevisionAndValidJsonChecksRejectInvalidRows()throws Exception{
  try(var db=connect()){
   assertThatThrownBy(()->insert(db,"bad-zero","invalid",0,"{}")).isInstanceOf(SQLException.class);
   assertThatThrownBy(()->insert(db,"bad-negative","invalid",-1,"{}")).isInstanceOf(SQLException.class);
   assertThatThrownBy(()->insert(db,"bad-json","invalid",1,"not-json")).isInstanceOf(SQLException.class);
  }
 }
 @Test void appendIsRemovedByTransactionRollback()throws Exception{
  try(var db=connect()){
   db.setAutoCommit(false);insert(db,"rolled-back","transaction",1,"{}");db.rollback();
   try(var sql=db.createStatement();var result=sql.executeQuery("SELECT COUNT(*) FROM iqc_pipeline_rollout_revision WHERE id='rolled-back'")){assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();}
  }
 }
 @Test void competingSameRevisionInsertsHaveExactlyOneWinner()throws Exception{
  var ready=new CountDownLatch(2);var go=new CountDownLatch(1);List<Future<Integer>> futures=new ArrayList<>();
  try(var workers=Executors.newFixedThreadPool(2)){
   for(int i=0;i<2;i++){String id="race-"+i;futures.add(workers.submit(()->{try(var db=connect()){ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier");try{insert(db,id,"race",1,"{}");return 0;}catch(SQLException error){return error.getErrorCode();}}}));}
   assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();List<Integer> results=new ArrayList<>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));assertThat(results).containsExactlyInAnyOrder(0,1062);
  }finally{go.countDown();}
  try(var db=connect();var sql=db.createStatement();var result=sql.executeQuery("SELECT COUNT(*) FROM iqc_pipeline_rollout_revision WHERE pipeline_id='race' AND revision_no=1")){assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(1);}
 }
}
