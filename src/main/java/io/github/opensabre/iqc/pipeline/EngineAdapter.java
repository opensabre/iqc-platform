package io.github.opensabre.iqc.pipeline;

/** Detection adapters must not create business actions; evaluation rejects side-effect capabilities. */
public interface EngineAdapter {
    String type();
    default boolean sideEffecting() { return false; }
    NodeResult execute(NodeContext context) throws Exception;
}
