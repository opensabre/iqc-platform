package io.github.opensabre.iqc.pipelineasset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.dao.PipelineRolloutRevisionMapper;
import io.github.opensabre.iqc.pipelineasset.model.PipelineRolloutRevision;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Append-only configuration history. Actual task routing is a separate materialization boundary. */
@Service
@RequiredArgsConstructor
public class PipelineRolloutService {
    private static final String SCHEMA="iqc-pipeline-rollout-revision-v1";
    private final PipelineAssetService pipelines;
    private final PipelineRolloutRevisionMapper revisions;
    private final ObjectMapper json;
    private final IqcDataScope scope;

    public enum Change { ACTIVATE, EXPAND, REDUCE, REPLACE, RESUME, ROLLBACK }
    public record Snapshot(String schemaVersion,String pipelineId,int revisionNo,String previousContentHash,
            PipelineRolloutPolicy policy,String policyHash,String baselineReleaseHash,String candidateReleaseHash,Change change) { }
    public record Revision(String id,int revisionNo,String contentHash,Snapshot snapshot,Instant createdTime) { }
    public record Current(int revisionNo,Revision revision) { }

    public Current current(String id){
        requireCanonicalAsset(id);var row=latest(id,false);
        return row==null?new Current(0,null):new Current(row.getRevisionNo(),view(id,row));
    }
    /** Cursor pagination retains every immutable revision, including those beyond the first page. */
    public List<Revision> history(String id,Integer beforeRevision,int limit){
        if(limit<1||limit>100||beforeRevision!=null&&beforeRevision<1)throw IqcException.invalidArgument("无效灰度修订分页");
        requireCanonicalAsset(id);
        var query=Wrappers.<PipelineRolloutRevision>lambdaQuery().eq(PipelineRolloutRevision::getPipelineId,id);
        if(beforeRevision!=null)query.lt(PipelineRolloutRevision::getRevisionNo,beforeRevision);
        return revisions.selectList(query.orderByDesc(PipelineRolloutRevision::getRevisionNo).last("LIMIT "+limit))
                .stream().map(row->view(id,row)).toList();
    }
    @Transactional
    public Revision append(String id,int expectedRevision,PipelineRolloutPolicy policy){
        if(expectedRevision<0||expectedRevision==Integer.MAX_VALUE||policy==null||!id.equals(policy.pipelineId()))
            throw IqcException.invalidArgument("灰度策略或预期修订无效");
        String actorId=scope.actorUserId();
        pipelines.lockForRollout(id);
        // Locking read sees the latest committed revision even in an existing REPEATABLE_READ transaction.
        var previous=latest(id,true);
        int current=previous==null?0:previous.getRevisionNo();
        if(current!=expectedRevision)throw IqcException.invalidState("灰度策略修订已变化，请重新读取");
        var prior=previous==null?null:view(id,previous);
        String policyHash=hash(policy);
        if(prior!=null&&policyHash.equals(prior.snapshot().policyHash()))throw IqcException.invalidArgument("灰度策略没有变化");
        Change change=change(prior==null?null:prior.snapshot().policy(),policy);
        var sources=pipelines.rolloutSources(id,policy);
        // Reuse all exact-publication and single-variable guards without creating a task.
        policy.preview(actorId,"policy-validation",sources.baseline(),sources.candidate(),json);
        var snapshot=new Snapshot(SCHEMA,id,current+1,prior==null?null:prior.contentHash(),policy,policyHash,
                sources.baseline().contentHash(),sources.candidate().contentHash(),change);
        var row=new PipelineRolloutRevision();row.setPipelineId(id);row.setRevisionNo(current+1);
        row.setSnapshotJson(write(snapshot));row.setContentHash(PipelineAssetService.hash(row.getSnapshotJson()));
        revisions.insert(row);
        return view(id,row);
    }
    private Change change(PipelineRolloutPolicy previous,PipelineRolloutPolicy next){
        if(previous==null){
            if(next.state()!=PipelineRolloutPolicy.State.ACTIVE)throw IqcException.invalidArgument("首次灰度策略必须为启用状态");
            return Change.ACTIVATE;
        }
        boolean sameCohort=Objects.equals(previous.baselineVersionNo(),next.baselineVersionNo())
                &&Objects.equals(previous.candidateVersionNo(),next.candidateVersionNo())
                &&Objects.equals(previous.cohortSalt(),next.cohortSalt())&&Objects.equals(previous.variable(),next.variable());
        if(next.state()==PipelineRolloutPolicy.State.ROLLED_BACK){
            if(previous.state()!=PipelineRolloutPolicy.State.ACTIVE||!sameCohort
                    ||!Objects.equals(previous.candidateBasisPoints(),next.candidateBasisPoints()))
                throw IqcException.invalidArgument("回退必须保留当前版本、比例、盐及变量声明");
            return Change.ROLLBACK;
        }
        if(!sameCohort)return Change.REPLACE;
        if(previous.state()==PipelineRolloutPolicy.State.ROLLED_BACK)return Change.RESUME;
        return next.candidateBasisPoints()>previous.candidateBasisPoints()?Change.EXPAND:Change.REDUCE;
    }
    private void requireCanonicalAsset(String id){
        var asset=pipelines.get(id);
        if(!Objects.equals(id,asset.getId()))throw IqcException.invalidArgument("流水线标识必须与原资产一致");
    }
    private PipelineRolloutRevision latest(String id,boolean lock){
        return revisions.selectOne(Wrappers.<PipelineRolloutRevision>lambdaQuery().eq(PipelineRolloutRevision::getPipelineId,id)
                .orderByDesc(PipelineRolloutRevision::getRevisionNo).last(lock?"LIMIT 1 FOR UPDATE":"LIMIT 1"));
    }
    private Revision view(String id,PipelineRolloutRevision row){
        try{
            if(row==null||!id.equals(row.getPipelineId())||row.getRevisionNo()==null||row.getRevisionNo()<1
                    ||!validHash(row.getContentHash())||!row.getContentHash().equals(PipelineAssetService.hash(row.getSnapshotJson())))throw new IllegalArgumentException();
            var snapshot=json.readValue(row.getSnapshotJson(),Snapshot.class);
            if(!SCHEMA.equals(snapshot.schemaVersion())||!id.equals(snapshot.pipelineId())||snapshot.revisionNo()!=row.getRevisionNo()
                    ||snapshot.policy()==null||!id.equals(snapshot.policy().pipelineId())||snapshot.change()==null
                    ||!hash(snapshot.policy()).equals(snapshot.policyHash())||!validHash(snapshot.baselineReleaseHash())||!validHash(snapshot.candidateReleaseHash())
                    ||(snapshot.revisionNo()==1?snapshot.previousContentHash()!=null:!validHash(snapshot.previousContentHash())))throw new IllegalArgumentException();
            return new Revision(row.getId(),row.getRevisionNo(),row.getContentHash(),snapshot,row.getCreatedTime()==null?null:row.getCreatedTime().toInstant());
        }catch(Exception invalid){throw IqcException.invalidState("灰度策略修订证据无效");}
    }
    private static boolean validHash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private String hash(Object value){return PipelineAssetService.hash(write(value));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw IqcException.invalidState("无法冻结灰度策略");}}
}
