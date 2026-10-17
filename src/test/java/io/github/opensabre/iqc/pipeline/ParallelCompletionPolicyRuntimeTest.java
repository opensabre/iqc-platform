package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises explicit early completion against real workers without invoking external providers. */
class ParallelCompletionPolicyRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();

    private PipelineDefinition.Node node(String id, List<String> dependencies) {
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject(id + "_value").put("type", "number");
        return new PipelineDefinition.Node(id, "TEST", id, "1", dependencies, Map.of(), null,
                null, 10000, 0, PipelineDefinition.OnError.FAIL_PIPELINE, null, schema, true);
    }

    private NodeResult success(String id) {
        return new NodeResult(NodeResult.Status.SUCCESS, List.of(),
                Map.of(id + "_value", json.getNodeFactory().numberNode(1)), Map.of(), null);
    }

    private PipelineDefinition.Node executionPolicy(PipelineDefinition.Node node, int retries,
            PipelineDefinition.OnError onError, String fallback, boolean enabled) {
        return new PipelineDefinition.Node(node.nodeId(), node.type(), node.name(), node.version(),
                node.dependsOn(), node.inputMapping(), node.config(), node.condition(), node.timeoutMs(),
                retries, onError, fallback, node.outputSchema(), enabled);
    }

    @Test void requiredNodeRetriesBeforeSatisfactionAndRetainsBothAttempts() {
        var calls = new AtomicInteger();
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) {
                calls.incrementAndGet();
                assertEquals("required", context.node().nodeId());
                return context.attempt() == 1 ? NodeResult.failure(NodeResult.Status.ERROR, "temporary") : success("required");
            }
        };
        var required = executionPolicy(node("required", List.of()), 1, PipelineDefinition.OnError.FAIL_PIPELINE, null, true);
        try (var runtime = new PipelineRuntime(List.of(adapter), 1, 4)) {
            var run = runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                    List.of(required, node("dependent", List.of("required"))),
                    new PipelineDefinition.CompletionPolicy(List.of("required"), null)),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.EVALUATION);
            assertEquals(2, calls.get());
            assertEquals(PipelineRuntime.Status.SUCCESS, run.status());
            assertEquals(PipelineRuntime.CompletionReason.POLICY_SATISFIED, run.completionReason());
            assertEquals(List.of(1, 2, 0), run.trace().stream().map(PipelineRuntime.Trace::attempt).toList());
            assertEquals(List.of(NodeResult.Status.ERROR, NodeResult.Status.SUCCESS, NodeResult.Status.SKIPPED),
                    run.trace().stream().map(t -> t.result().status()).toList());
        }
    }

    @Test void successfulFallbackSatisfiesSourceRequirementAndPublishesItsDeclaredValue() {
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) {
                return context.node().nodeId().equals("required")
                        ? NodeResult.failure(NodeResult.Status.ERROR, "primary unavailable") : success("backup");
            }
        };
        var required = executionPolicy(node("required", List.of()), 0, PipelineDefinition.OnError.FALLBACK, "backup", true);
        var backup = executionPolicy(node("backup", List.of()), 0, PipelineDefinition.OnError.CONTINUE, null, false);
        var condition = new PipelineDefinition.Condition("/variables/backup_value", PipelineDefinition.Operator.EQUALS,
                json.getNodeFactory().numberNode(1));
        try (var runtime = new PipelineRuntime(List.of(adapter), 1, 4)) {
            var run = runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                    List.of(required, backup), new PipelineDefinition.CompletionPolicy(List.of("required"), condition)),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION);
            assertEquals(PipelineRuntime.Status.SUCCESS, run.status());
            assertEquals(PipelineRuntime.CompletionReason.POLICY_SATISFIED, run.completionReason());
            assertEquals(List.of("required", "backup"), run.trace().stream().map(PipelineRuntime.Trace::nodeId).toList());
            assertEquals(Map.of("backup_value", json.getNodeFactory().numberNode(1)), run.variables());
        }
    }

    @Test void failedRequiredNodeCannotSatisfyPolicyEvenWhenOtherWorkSucceeds() {
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) {
                return context.node().nodeId().equals("required")
                        ? NodeResult.failure(NodeResult.Status.ERROR, "unavailable") : success("other");
            }
        };
        var required = executionPolicy(node("required", List.of()), 0, PipelineDefinition.OnError.CONTINUE, null, true);
        try (var runtime = new PipelineRuntime(List.of(adapter), 1, 4)) {
            var run = runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                    List.of(required, node("other", List.of())), new PipelineDefinition.CompletionPolicy(List.of("required"), null)),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION);
            assertEquals(PipelineRuntime.Status.PARTIAL_SUCCESS, run.status());
            assertNull(run.completionReason());
            assertFalse(run.variables().containsKey("required_value"));
            assertEquals(1, run.variables().get("other_value").intValue());
        }
    }

    @Test void progressInterruptionStopsRetryAndPreservesCancellationStatus() {
        var calls = new AtomicInteger();
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) {
                calls.incrementAndGet();
                return NodeResult.failure(NodeResult.Status.ERROR, "retryable");
            }
        };
        var required = executionPolicy(node("required", List.of()), 3, PipelineDefinition.OnError.FAIL_PIPELINE, null, true);
        try (var runtime = new PipelineRuntime(List.of(adapter), 1, 4)) {
            var run = runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                    List.of(required), new PipelineDefinition.CompletionPolicy(List.of("required"), null)),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION,
                    trace -> Thread.currentThread().interrupt());
            assertEquals(1, calls.get());
            assertEquals(PipelineRuntime.Status.CANCELLED, run.status());
            assertNull(run.completionReason());
            assertEquals(1, run.trace().size());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void progressFailureCancelsSiblingAndPreservesCleanupFailureAsSuppressed() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var leaseLost = new IllegalStateException("lease lost");
        var cleanupFailure = new IllegalStateException("cleanup callback");
        var callbacks = new AtomicInteger();
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) throws Exception {
                if (context.node().nodeId().equals("slow")) {
                    started.countDown();
                    try { release.await(); }
                    catch (InterruptedException failure) { interrupted.countDown(); throw failure; }
                } else assertTrue(started.await(3, TimeUnit.SECONDS));
                return success(context.node().nodeId());
            }
        };
        try (var runtime = new PipelineRuntime(List.of(adapter), 2, 4)) {
            var failure = assertThrows(IllegalStateException.class, () -> runtime.execute(
                    new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                            List.of(node("slow", List.of()), node("required", List.of())),
                            new PipelineDefinition.CompletionPolicy(List.of("required"), null)),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION,
                    trace -> { throw callbacks.incrementAndGet() == 1 ? leaseLost : cleanupFailure; }));
            assertSame(leaseLost, failure);
            assertEquals(List.of(cleanupFailure), List.of(failure.getSuppressed()));
            assertEquals(2, callbacks.get());
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void budgetWaitIsPropagatedWithoutRetryOrFallback() {
        var wait = new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException(
                "MODEL_TOKEN_BUDGET_EXHAUSTED", "a".repeat(64));
        var calls = new AtomicInteger();
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) { calls.incrementAndGet(); throw wait; }
        };
        var required = executionPolicy(node("required", List.of()), 3, PipelineDefinition.OnError.FALLBACK, "backup", true);
        var backup = executionPolicy(node("backup", List.of()), 0, PipelineDefinition.OnError.CONTINUE, null, false);
        try (var runtime = new PipelineRuntime(List.of(adapter), 1, 4)) {
            var failure = assertThrows(io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException.class,
                    () -> runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                            List.of(required, backup), new PipelineDefinition.CompletionPolicy(List.of("required"), null)),
                            json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION));
            assertSame(wait, failure);
            assertEquals(1, calls.get());
        }
    }

    @Test void fastRequiredNodeCancelsEarlierSlowSiblingAndNeverSubmitsDependent() throws Exception {
        var slowStarted = new CountDownLatch(1);
        var slowInterrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) throws Exception {
                String id = context.node().nodeId();
                if (id.equals("slow")) {
                    slowStarted.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) { slowInterrupted.countDown(); throw interrupted; }
                } else if (id.equals("required")) {
                    assertTrue(slowStarted.await(3, TimeUnit.SECONDS));
                } else fail("policy must prevent dependent submission");
                return success(id);
            }
        };
        var policy = new PipelineDefinition.CompletionPolicy(List.of("required"), null);
        try (var runtime = new PipelineRuntime(List.of(adapter), 2, 4)) {
            var run = runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                    List.of(node("slow", List.of()), node("required", List.of()),
                            node("dependent", List.of("required"))), policy),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION);
            assertEquals(PipelineRuntime.Status.SUCCESS, run.status());
            assertEquals(PipelineRuntime.CompletionReason.POLICY_SATISFIED, run.completionReason());
            assertTrue(slowInterrupted.await(3, TimeUnit.SECONDS));
            var traces = run.trace().stream().collect(java.util.stream.Collectors.toMap(PipelineRuntime.Trace::nodeId, t -> t));
            assertEquals(3, traces.size());
            assertEquals(NodeResult.Status.SUCCESS, traces.get("required").result().status());
            assertEquals(NodeResult.Status.CANCELLED, traces.get("slow").result().status());
            assertTrue(PipelineRuntime.policySkipped(run, traces.get("slow")));
            assertEquals(NodeResult.Status.SKIPPED, traces.get("dependent").result().status());
            assertEquals(0, traces.get("dependent").attempt());
            assertEquals(Map.of("required_value", json.getNodeFactory().numberNode(1)), run.variables());
        } finally { release.countDown(); }
    }

    @Test void missingConditionValueDoesNotShortCircuitSuccessfulGraph() {
        var calls = new AtomicInteger();
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) { calls.incrementAndGet(); return success(context.node().nodeId()); }
        };
        var condition = new PipelineDefinition.Condition("/input/absent", PipelineDefinition.Operator.EQUALS,
                json.getNodeFactory().booleanNode(true));
        try (var runtime = new PipelineRuntime(List.of(adapter), 1, 4)) {
            var run = runtime.execute(new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,
                    List.of(node("required", List.of()), node("dependent", List.of("required"))),
                    new PipelineDefinition.CompletionPolicy(List.of("required"), condition)),
                    json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION);
            assertEquals(2, calls.get());
            assertEquals(PipelineRuntime.Status.SUCCESS, run.status());
            assertNull(run.completionReason());
            assertEquals(2, run.variables().size());
        }
    }

    @Test void invalidPoliciesAreRejectedBeforeAdaptersExecute() {
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "TEST"; }
            public NodeResult execute(NodeContext context) { throw new AssertionError("invalid policy executed"); }
        };
        var nodes = List.of(node("required", List.of()), node("sibling", List.of()));
        var valid = new PipelineDefinition.CompletionPolicy(List.of("required"), null);
        var siblingRead = new PipelineDefinition.Condition("/variables/sibling_value", PipelineDefinition.Operator.EXISTS, null);
        var definitions = List.of(
                new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA, nodes, valid),
                new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA, nodes),
                new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA, nodes, new PipelineDefinition.CompletionPolicy(List.of("missing"), null)),
                new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA, nodes, new PipelineDefinition.CompletionPolicy(List.of("required", "required"), null)),
                new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA, nodes, new PipelineDefinition.CompletionPolicy(List.of("required"), siblingRead)));
        try (var runtime = new PipelineRuntime(List.of(adapter), 2, 4)) {
            for (var definition : definitions) assertThrows(IllegalArgumentException.class,
                    () -> runtime.execute(definition, json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION));
        }
    }

    @Test void historicalDefinitionAndRunOmitNewFields() throws Exception {
        var definition = new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA, List.of(node("old", List.of())));
        var serialized = json.valueToTree(definition);
        assertFalse(serialized.has("completionPolicy"));
        var oldJson = serialized.toString();
        assertEquals(oldJson, json.writeValueAsString(json.readValue(oldJson, PipelineDefinition.class)));
        var run = new PipelineRuntime.Run(PipelineRuntime.Status.SUCCESS, List.of(), List.of(), Map.of(), false);
        assertFalse(json.valueToTree(run).has("completionReason"));
    }
}
