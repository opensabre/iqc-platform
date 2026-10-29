package io.github.opensabre.iqc.modelprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.modelprofile.model.IqcModelProfile;
import io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/** Tests a persisted model profile without exposing provider credentials or raw failures. */
@Service
public class ModelConnectionTestService {
    private final IqcModelProfileMapper mapper;
    private final SnapshotChatModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final io.github.opensabre.iqc.shared.IqcDataScope scope;

    public ModelConnectionTestService(IqcModelProfileMapper mapper, SnapshotChatModelRouter modelRouter,
                                      ObjectMapper objectMapper) {
        this(mapper,modelRouter,objectMapper,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ModelConnectionTestService(IqcModelProfileMapper mapper,SnapshotChatModelRouter modelRouter,ObjectMapper objectMapper,
            io.github.opensabre.iqc.shared.IqcDataScope scope){
        this.scope=scope;
        this.mapper = mapper;
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
    }

    /** Sends a minimal prompt and reports only safe connection diagnostics. */
    public ConnectionTestResult test(String id) {
        IqcModelProfile profile = mapper.selectById(id);
        if (profile == null) throw IqcException.notFound("模型配置不存在: " + id);
        long started = System.nanoTime();
        try {
            String owner=scope==null?null:scope.owner();if("system".equals(owner))owner=null;
            var response=modelRouter.callProfile(new Prompt("请只回复 OK"),objectMapper.valueToTree(profile),
                    new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context(
                            "model-connection:"+profile.getId()+":version:"+profile.getVersionNo(),null,owner));
            if (response == null) throw new IllegalStateException("empty response");
            return new ConnectionTestResult(true, "连接成功", elapsedMillis(started), profile.getProvider(), profile.getModelName());
        } catch (io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException denied) {
            return new ConnectionTestResult(false,"连接检查未能完成，请查看模型调用记录及限额",elapsedMillis(started),profile.getProvider(),profile.getModelName());
        } catch (RuntimeException exception) {
            // Provider exceptions may contain request headers or remote payloads, so never return them to the client.
            return new ConnectionTestResult(false, "连接失败，请检查 Endpoint、密钥引用和模型名称", elapsedMillis(started),
                profile.getProvider(), profile.getModelName());
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** Safe model connection result returned to administrators. */
    public record ConnectionTestResult(boolean success, String message, long latencyMillis,
                                       String provider, String modelName) { }
}
