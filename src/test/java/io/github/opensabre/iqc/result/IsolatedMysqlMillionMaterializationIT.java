package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.selector.SelectorDefinition;
import io.github.opensabre.iqc.task.BulkTaskLifecycleService;
import io.github.opensabre.iqc.task.BulkTaskMaterializationService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import java.util.*;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in million real-message freezes and cross-JVM crashes; controlled organization, no external provider. */
class IsolatedMysqlMillionMaterializationIT {
    static final int ROWS=1_000_000;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate db;
    static ObjectMapper json;

    @BeforeAll static void start() throws Exception {
        // Reuse the existing real Gold/dataset/trial/measured publication preparation, not a forged release row.
        IsolatedMysqlMeasuredPublicationIT.start();
        new IsolatedMysqlMeasuredPublicationIT().realMeasuredPublicationMaterializesAndExecutesFrozenProductionInput();
        context=new AnnotationConfigApplicationContext(IsolatedMysqlMeasuredPublicationIT.Config.class);
        db=new JdbcTemplate(context.getBean(DataSource.class));json=context.getBean(ObjectMapper.class);
        org.mockito.Mockito.when(context.getBean(io.github.opensabre.iqc.shared.IqcDataScope.class).owner()).thenReturn("annotator");
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));
    }
    @AfterAll static void close(){if(context!=null)context.close();IsolatedMysqlMeasuredPublicationIT.close();}

    @Test void millionActualInputsSurviveEarlyMiddleLateCommittedCrashesAndUncommittedCrash() throws Exception {
        long began=System.nanoTime();
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String number="a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n";
        assertEquals(ROWS,db.update("INSERT INTO iqc_conversation(id,source_file_name,status,message_count,created_by,owner_group_id,created_time,channel,source_fingerprint) "
                +"SELECT CONCAT('million',LPAD("+number+",7,'0')),'processed','IMPORTED',1,'annotator','group','2020-01-01','MILLION_MATERIALIZE',SHA2(CONCAT('input:',"+number+"),256) FROM "
                +digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d CROSS JOIN "+digits+" e CROSS JOIN "+digits+" f"));
        assertEquals(ROWS,db.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) "
                +"SELECT CONCAT('msg',id),id,1,'agent',CONCAT('processed ',id),'00:00:00',CONCAT('processed ',id),1 FROM iqc_conversation WHERE channel='MILLION_MATERIALIZE'"));
        System.out.println("MILLION_MATERIALIZE seeded conversations="+ROWS+" messages="+ROWS+" ms="+(System.nanoTime()-began)/1_000_000);
        String pipeline=db.queryForObject("SELECT DISTINCT v.pipeline_id FROM iqc_pipeline_asset_version v JOIN iqc_pipeline_asset p ON p.id=v.pipeline_id WHERE p.name='Measured publication' AND v.version_no=1",String.class);
        var assets=context.getBean(PipelineAssetService.class);var prepared=assets.publishedReleaseForTask(pipeline,1);
        var selector=new SelectorDefinition(SelectorDefinition.SCHEMA,new SelectorDefinition.Condition(null,null,"channel",SelectorDefinition.Operator.EQ,json.valueToTree("MILLION_MATERIALIZE")),null,List.of(),List.of(),null,null,1000);
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user-annotator","annotator",false,"group");
        var bulk=context.getBean(BulkTaskMaterializationService.class);var lifecycle=context.getBean(BulkTaskLifecycleService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(bulk));
        var parent=bulk.create(pipeline,1,selector,bound,"Million actual materialization",1000,1,"million_actual_input_v1");
        parent=lifecycle.queue(parent.getId());String id=parent.getId(),eid=parent.getCurrentExecutionId();assertTrue(lifecycle.claim(id,eid));
        var tasks=context.getBean(SqlSessionTemplate.class).getMapper(InspectionTaskMapper.class);

        // Kill while child INSERT/link writes are still in the original transaction, before cursor UPDATE.
        var original=tasks.selectById(id).getBulkProgressJson();int tasksBefore=countTasks();
        try(var uncommitted=new BulkRestartProcess("materialize-uncommitted",id,eid)){
            assertEquals("BEFORE_CHECKPOINT:"+uncommitted.pid(),uncommitted.signal("BEFORE_CHECKPOINT:"));uncommitted.kill();
        }
        assertEquals(original,tasks.selectById(id).getBulkProgressJson());assertEquals(tasksBefore,countTasks());
        assertEquals(0,childCount(id));System.out.println("MILLION_MATERIALIZE uncommitted rollback parent="+id+" eid="+eid);
        // The rolled-back transaction leaves the same RUNNING generation available to a fresh service JVM.
        var checkpoints=new ArrayDeque<>(List.of(1L,500_000L,999_000L));boolean done=false;
        Map<String,String> committed=new TreeMap<>();
        while(!done){
            try(var worker=new BulkRestartProcess("materialize",id,eid)){
                while(true){
                    String signal=worker.signal("MATERIALIZED:");var progress=BulkTaskMaterializationService.readProgress(json,tasks.selectById(id).getBulkProgressJson());
                    assertEquals("MATERIALIZED:"+progress.nextShardNo()+":"+progress.selectedConversations()+":"+progress.scanComplete(),signal);
                    assertEquals(eid,tasks.selectById(id).getCurrentExecutionId());
                    assertTrue(progress.selectedConversations()<=ROWS);done=progress.scanComplete();
                    if(!checkpoints.isEmpty()&&progress.selectedConversations()>=checkpoints.peek()){
                        long checkpoint=checkpoints.remove();committed=digests(id);String before=tasks.selectById(id).getBulkProgressJson();long pid=worker.pid();worker.kill();
                        assertEquals(before,tasks.selectById(id).getBulkProgressJson());assertEquals(committed,digests(id));
                        System.out.println("MILLION_MATERIALIZE committed crash threshold="+checkpoint+" pid="+pid+" selected="+progress.selectedConversations()+" shards="+childCount(id));
                        if(checkpoint==1L)db.update("UPDATE iqc_conversation_message SET content='live archive mutation after first commit' WHERE conversation_id='million0000000'");
                        recover(id,eid,lifecycle);assertEquals(committed,digests(id));break;
                    }
                    if(done){worker.requireCleanExit();break;}
                    worker.release();
                }
            }
        }
        assertTrue(checkpoints.isEmpty());var progress=BulkTaskMaterializationService.readProgress(json,tasks.selectById(id).getBulkProgressJson());
        assertEquals(ROWS,progress.selectedConversations());assertTrue(progress.scanComplete());
        assertEquals(eid,tasks.selectById(id).getCurrentExecutionId());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_task_execution WHERE task_id=?",Integer.class,id));
        long messages=0,conversations=0;var ids=db.queryForList("SELECT id FROM iqc_inspection_task WHERE bulk_parent_task_id=? ORDER BY bulk_shard_no",String.class,id);
        var seen=new BitSet(ROWS);var shardNumbers=new HashSet<Integer>();
        for(String childId:ids){
            var child=tasks.selectById(childId);assertTrue(shardNumbers.add(child.getBulkShardNo()));assertEquals("annotator",child.getCreatedBy());
            var snapshot=json.readTree(child.getRuleSnapshotJson());var inputs=snapshot.path("productionInputs");var body=inputs.path("conversations");
            assertEquals(io.github.opensabre.iqc.selector.SelectorAssetService.hash(body.toString()),inputs.path("contentHash").asText());
            assertEquals(prepared.contentHash(),snapshot.at("/pipelineSnapshot/contentHash").asText());
            assertTrue(body.size()>0&&body.size()<=1000);assertTrue(child.getRuleSnapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16*1024*1024);
            var selected=json.readTree(child.getConversationIdsJson());assertEquals(selected.size(),body.size());
            assertEquals(body.size(),child.getTotalMessages());
            for(int n=0;n<body.size();n++){
                var input=body.get(n);String conversation=input.path("conversationId").asText();assertEquals(selected.get(n).asText(),conversation);
                assertTrue(conversation.matches("million[0-9]{7}"));int index=Integer.parseInt(conversation.substring(7));assertTrue(index<ROWS);assertFalse(seen.get(index));seen.set(index);
                assertEquals(1,input.path("messages").size());var message=input.path("messages").get(0);
                assertEquals("msg"+conversation,message.path("id").asText());assertEquals("processed "+conversation,message.path("content").asText());
                conversations++;messages++;
            }
        }
        assertEquals(ROWS,seen.cardinality());assertEquals(ROWS,conversations);assertEquals(ROWS,messages);
        String before=tasks.selectById(id).getBulkProgressJson();assertNull(bulk.materializeNext(id,eid).childTaskId());
        assertEquals(before,tasks.selectById(id).getBulkProgressJson());assertEquals(ids.size(),childCount(id));
        assertEquals(ids.size(),db.queryForObject("SELECT COUNT(DISTINCT bulk_shard_no) FROM iqc_inspection_task WHERE bulk_parent_task_id=?",Integer.class,id));
        System.out.println("MILLION_MATERIALIZE complete parent="+id+" eid="+eid+" shards="+ids.size()+" scanned="+progress.scannedConversations()+" conversations="+conversations+" messages="+messages+" elapsedMs="+(System.nanoTime()-began)/1_000_000);
    }
    private static int countTasks(){return db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task",Integer.class);}
    private static int childCount(String id){return db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE bulk_parent_task_id=?",Integer.class,id);}
    private static Map<String,String> digests(String id){
        var output=new TreeMap<String,String>();db.query("SELECT id,SHA2(rule_snapshot_json,256) digest FROM iqc_inspection_task WHERE bulk_parent_task_id=? ORDER BY id",rs->{output.put(rs.getString("id"),rs.getString("digest"));},id);return output;
    }
    private static void recover(String id,String eid,BulkTaskLifecycleService lifecycle) throws Exception {
        // Only age the test-owned heartbeat; actual original worker discovery/coordinator performs recovery.
        db.update("UPDATE iqc_inspection_task SET updated_time=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 10 MINUTE) WHERE id=?",id);
        try(var restarted=new BulkRestartProcess("recover-tick",id,eid)){
            assertEquals("RESUMED:"+restarted.pid(),restarted.signal("RESUMED:"));restarted.requireCleanExit();
        }
        assertTrue(lifecycle.claim(id,eid));
    }
}
