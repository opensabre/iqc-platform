package io.github.opensabre.iqc.result;

import io.github.opensabre.iqc.task.BulkTaskLifecycleService;
import io.github.opensabre.iqc.task.InspectionTaskService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Dedicated service JVMs share only isolated SQL state; no application scheduler or external clients run. */
final class BulkRestartProcess implements AutoCloseable {
    private final Process process;
    private final BufferedReader output;

    BulkRestartProcess(String command, String parentId, String executionId) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String agent = Path.of(System.getProperty("user.home"), ".m2", "repository", "net", "bytebuddy",
                "byte-buddy-agent", "1.18.10", "byte-buddy-agent-1.18.10.jar").toString();
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agent, "-cp", classpath, Main.class.getName(), command, parentId, executionId)
                .redirectErrorStream(true).start();
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    String signal(String prefix) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            var messages = new StringBuilder();
            try {
                for (String line; (line = output.readLine()) != null;) {
                    messages.append(line).append('\n');
                    if (line.startsWith(prefix)) return line;
                }
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            throw new IllegalStateException("Child exited without " + prefix + ":\n" + messages);
        }).get(60, TimeUnit.SECONDS);
    }

    long pid() { return process.pid(); }
    void kill() throws Exception {
        process.destroyForcibly();
        process.onExit().get(10, TimeUnit.SECONDS);
        assertFalse(process.isAlive());
    }
    void requireCleanExit() throws Exception { assertEquals(0, process.onExit().get(10, TimeUnit.SECONDS).exitValue()); }
    @Override public void close() throws Exception {
        if (process.isAlive()) kill();
        output.close(); process.getOutputStream().close();
    }

    /** Each invocation loads new production transaction proxies and database connections. */
    public static final class Main {
        public static void main(String[] args) throws Exception {
            IsolatedMysqlMigrationIT.verifyIsolatedServer();
            try (var child = new AnnotationConfigApplicationContext(IsolatedMysqlMeasuredPublicationIT.Config.class)) {
                var lifecycle = child.getBean(BulkTaskLifecycleService.class);
                if ("claim-hold".equals(args[0])) {
                    assertTrue(lifecycle.claim(args[1], args[2]));
                    System.out.println("CLAIMED:" + ProcessHandle.current().pid()); System.out.flush();
                    System.in.read(); // Parent kills this process after the claim transaction has committed.
                } else if ("recover-tick".equals(args[0])) {
                    int recovered = child.getBean(InspectionTaskService.class).recoverStaleExecutions(Duration.ofMinutes(1), false);
                    assertTrue(recovered >= 1);
                    assertTrue(lifecycle.claim(args[1], args[2]));
                    lifecycle.tick(args[1], args[2]);
                    System.out.println("RESUMED:" + ProcessHandle.current().pid()); System.out.flush();
                } else throw new IllegalArgumentException("Unknown isolated command");
            }
        }
    }
}
