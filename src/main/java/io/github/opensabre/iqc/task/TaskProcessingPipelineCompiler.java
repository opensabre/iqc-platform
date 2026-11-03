package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Maps task-owned engine order onto the existing sequential runtime, continuing after engine errors. */
public final class TaskProcessingPipelineCompiler {
    private TaskProcessingPipelineCompiler() { }

    /** Resolver must validate parameters, authorize dependencies and return a server-frozen detector node. */
    public static PipelineDefinition compile(TaskProcessingConfiguration.Processing processing,
            Function<TaskProcessingConfiguration.Engine, PipelineDefinition.Node> resolver) {
        Objects.requireNonNull(processing); Objects.requireNonNull(resolver);
        var nodes = new ArrayList<PipelineDefinition.Node>();
        for (var engine : processing.engines()) {
            var frozen = Objects.requireNonNull(resolver.apply(engine));
            if (!engine.id().equals(frozen.nodeId()))
                throw new IllegalArgumentException("Frozen engine identity differs from task configuration");
            String expectedType = engine.type() == TaskProcessingConfiguration.EngineType.REGEX ? "RULE" : engine.type().name();
            if (!expectedType.equals(frozen.type()))
                throw new IllegalArgumentException("Frozen engine type differs from task configuration");
            // Order is not a success dependency: a preceding engine's error must not skip the next engine.
            nodes.add(new PipelineDefinition.Node(frozen.nodeId(), frozen.type(), frozen.name(), frozen.version(),
                    List.of(), frozen.inputMapping(), frozen.config(), null, frozen.timeoutMs(), frozen.retries(),
                    PipelineDefinition.OnError.CONTINUE, null, frozen.outputSchema(), true));
        }
        return new PipelineDefinition(PipelineDefinition.SCHEMA, nodes);
    }
}
