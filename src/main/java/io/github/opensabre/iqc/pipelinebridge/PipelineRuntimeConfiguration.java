package io.github.opensabre.iqc.pipelinebridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.PipelineRuntime;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
@Configuration
public class PipelineRuntimeConfiguration {
    @Bean(destroyMethod="close")
    public PipelineRuntime pipelineRuntime(ObjectMapper json,ObjectProvider<InspectionExecutionService> detector,
            io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider mcpTools,
            @Value("${iqc.pipeline.max-workers:16}") int workers,@Value("${iqc.pipeline.max-queued:128}") int queued) {
        return new PipelineRuntime(List.of(new FrozenDetectorEngine("RULE",json,detector),new FrozenDetectorEngine("DLS",json,detector),
                new FrozenDetectorEngine("LLM",json,detector),new TranscriptMetricEngine(json),new SessionAggregationEngine(json),new SkillApiDetectorEngine(json,mcpTools)),workers,queued);
    }
}
