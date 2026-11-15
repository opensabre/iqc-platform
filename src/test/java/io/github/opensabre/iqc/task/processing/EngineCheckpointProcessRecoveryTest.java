package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises durable stage recovery across abruptly terminated JVMs without a shared database or model. */
class EngineCheckpointProcessRecoveryTest {
    @TempDir Path directory;

    @Test void freshJvmPreservesCommittedStageAndCallsOnlyRemainingLlmTags() throws Exception {
        runWorker("interrupt", 86);
        var before = store(directory).find("execution", "conversation").orElseThrow();
        assertThat(before.engines()).hasSize(1);
        assertThat(before.engines().getFirst().observations().getFirst().status()).isEqualTo(ObservationStatus.MATCHED);
        assertThat(Files.exists(directory.resolve("llm-request.json"))).isFalse();
        runWorker("resume", 0);
        var after = store(directory).find("execution", "conversation").orElseThrow();
        assertThat(after.input()).isEqualTo(before.input());
        assertThat(after.engines()).hasSize(2);
        assertThat(after.engines().getFirst()).isEqualTo(before.engines().getFirst());
        assertThat(after.engines().getLast().status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(after.engines().getLast().observations()).extracting(Observation::targetId).containsExactly("20");
        var request = new ObjectMapper().readTree(Files.readString(directory.resolve("llm-request.json")));
        assertThat(request.path("tags")).hasSize(1);
        assertThat(request.at("/tags/0/tagId").asText()).isEqualTo("20");
        assertThat(Files.readAllLines(directory.resolve("calls.txt"))).containsExactly("REGEX", "LLM");
    }

    private void runWorker(String mode, int expectedExit) throws Exception {
        Path log = directory.resolve(mode + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, Worker.class.getName(), directory.toString(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("child JVM finished: %s", mode).isTrue();
            assertThat(process.exitValue()).as("child JVM output: %s", Files.readString(log)).isEqualTo(expectedExit);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("child JVM cleanup").isTrue();
            }
        }
    }

    private static JdbcTemplate jdbc(Path directory) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:file:" + directory.resolve("checkpoint").toAbsolutePath()
                + ";MODE=MySQL;WRITE_DELAY=0");
        return new JdbcTemplate(source);
    }

    private static EngineResultSetStore store(Path directory) {
        return new EngineResultSetStore(jdbc(directory), new ObjectMapper());
    }

    /** Separate process entry point; the intentional halt bypasses all shutdown hooks. */
    public static final class Worker {
        public static void main(String[] arguments) throws Exception {
            Path directory = Path.of(arguments[0]);
            boolean interrupt = arguments[1].equals("interrupt");
            var mapper = new ObjectMapper();
            var store = store(directory);
            var regex = mapper.createObjectNode().put("schemaVersion", "iqc-unified-tags-v1");
            regex.putArray("tags").addObject().put("tagId", "10").put("ruleId", "100")
                    .put("ruleVersionNo", 1).put("expression", "退款");
            var llm = regex.deepCopy();
            llm.putObject("agentSnapshot");
            llm.withArray("tags").addObject().put("tagId", "20").put("ruleId", "200")
                    .put("ruleVersionNo", 2).put("prompt", "识别客户意向");
            LlmQualityProvider provider = new LlmQualityProvider() {
                @Override public LlmEvaluation evaluate(String content, JsonNode rule, String recordId) {
                    throw new AssertionError("unified tags must use the batch interface");
                }
                @Override public JsonNode evaluateTagBatch(JsonNode request, JsonNode agent, String recordId) {
                    try {
                        Files.writeString(directory.resolve("llm-request.json"), request.toString());
                        recordCall(directory, "LLM");
                        return mapper.readTree("{\"results\":[{\"tagId\":\"20\",\"status\":\"MATCHED\"}]}");
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
            };
            var regexAdapter = new UnifiedTagEngineAdapter(mapper, new RegexEngineAdapter(mapper),
                    new DlsEngineAdapter(mapper), new LlmEngineAdapter(provider, mapper), EngineType.REGEX);
            var llmAdapter = new UnifiedTagEngineAdapter(mapper, new RegexEngineAdapter(mapper),
                    new DlsEngineAdapter(mapper), new LlmEngineAdapter(provider, mapper), EngineType.LLM);
            var plan = List.of(
                    new MultiEngineExecutor.Step(Operation.LABELING, "tags-REGEX", EngineType.REGEX, 1, regex, (input, config) -> {
                        if (!interrupt) throw new AssertionError("a durable REGEX stage must not run again");
                        recordCall(directory, "REGEX");
                        return regexAdapter.execute(input, config);
                    }),
                    new MultiEngineExecutor.Step(Operation.LABELING, "tags-LLM", EngineType.LLM, 2, llm, llmAdapter));
            EngineExecutionResultSet initial;
            if (interrupt) {
                jdbc(directory).execute(Files.readString(Path.of(
                        "src/main/resources/db/migration/mysql/ddl/V1.1.33__ddl_add_engine_execution_result_set.sql")));
                initial = new EngineExecutionResultSet(SCHEMA_VERSION, "set", "task", "execution", "conversation",
                        new InputSnapshot("input", Instant.EPOCH, List.of(new Message("message", 1, "customer", null, "我要退款"))),
                        mapper.createObjectNode(), List.of());
            } else initial = store.find("execution", "conversation").orElseThrow();
            new MultiEngineExecutor(Clock.systemUTC()).execute(initial, plan, () -> false, next -> {
                store.save(next);
                if (interrupt && next.engines().size() == 1) Runtime.getRuntime().halt(86);
            });
        }

        private static void recordCall(Path directory, String engine) throws java.io.IOException {
            Files.writeString(directory.resolve("calls.txt"), engine + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }
}
