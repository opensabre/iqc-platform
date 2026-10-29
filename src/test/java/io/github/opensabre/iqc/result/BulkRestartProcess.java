package io.github.opensabre.iqc.result;

import io.github.opensabre.iqc.task.BulkTaskLifecycleService;
import io.github.opensabre.iqc.task.InspectionTaskService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
                "-javaagent:" + agent, "-Diqc.test.worker-schema="+IsolatedMysqlTaskExecutionStateIT.SCHEMA, "-cp", classpath, Main.class.getName(), command, parentId, executionId)
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

    void release() throws Exception {
        process.getOutputStream().write(1);process.getOutputStream().flush();
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

    /** Real worker discovery, pool dispatch and coordinator; only the organization projection is controlled. */
    private static void discoverWithRealWorker(AnnotationConfigApplicationContext child,String parentId,String executionId) throws Exception {
        var scope=child.getBean(io.github.opensabre.iqc.shared.IqcDataScope.class);
        var actors=child.getBean(io.github.opensabre.iqc.schedule.ScheduledActorAccessService.class);
        org.mockito.Mockito.when(actors.resolve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(io.github.opensabre.iqc.task.BulkTaskMaterializationService.GRANTS)))
                .thenAnswer(call -> {
                    io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound bound=call.getArgument(0);
                    return new io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved(bound.userId(),bound.owner(),bound.all(),bound.groupId(),
                            java.util.Set.of(),io.github.opensabre.iqc.task.BulkTaskMaterializationService.GRANTS,java.time.Instant.now());
                });
        org.mockito.Mockito.when(scope.asScheduledActor(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(1)).get());
        var coordinator=org.mockito.Mockito.spy(new io.github.opensabre.iqc.task.BulkTaskCoordinator(child.getBean(BulkTaskLifecycleService.class),actors,scope));
        var providers=new org.springframework.beans.factory.support.StaticListableBeanFactory();providers.addBean("bulk",coordinator);
        var pool=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);pool.setMaxPoolSize(1);pool.setQueueCapacity(100);pool.initialize();
        try {
            var mapper=child.getBean(org.mybatis.spring.SqlSessionTemplate.class).getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class);
            var worker=new io.github.opensabre.iqc.task.DurableInspectionWorker(mapper,child.getBean(InspectionTaskService.class),
                    child.getBean(InspectionExecutionService.class),pool,null,providers.getBeanProvider(io.github.opensabre.iqc.task.BulkTaskCoordinator.class));
            org.springframework.test.util.ReflectionTestUtils.setField(worker,"timeoutMinutes",1L);
            worker.validate();worker.discoverQueuedTasks();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<deadline) {
                var parent=mapper.selectById(parentId);
                boolean targetDispatched=org.mockito.Mockito.mockingDetails(coordinator).getInvocations().stream()
                        .anyMatch(call -> call.getMethod().getName().equals("executePersisted")
                                && java.util.Arrays.equals(call.getArguments(),new Object[]{parentId,executionId}));
                if(targetDispatched && "QUEUED".equals(parent.getStatus()) && pool.getActiveCount()==0 && pool.getThreadPoolExecutor().getQueue().isEmpty()) {
                    assertEquals(executionId,parent.getCurrentExecutionId());
                    org.mockito.Mockito.verify(coordinator,org.mockito.Mockito.times(1)).executePersisted(parentId,executionId);
                    return;
                }
                Thread.sleep(20);
            }
            fail("Real worker did not recover and yield the parent within the bounded wait");
        } finally {pool.shutdown();}
    }

    /** Each invocation loads new production transaction proxies and database connections. */
    public static final class Main {
        public static void main(String[] args) throws Exception {
            IsolatedMysqlMigrationIT.verifyIsolatedServer();
            try (var child = new AnnotationConfigApplicationContext(IsolatedMysqlMeasuredPublicationIT.Config.class)) {
                var lifecycle = child.getBean(BulkTaskLifecycleService.class);
                if ("compete".equals(args[0])) {
                    System.out.println("READY:" + ProcessHandle.current().pid());System.out.flush();
                    if(System.in.read()<0)throw new IllegalStateException("Claim barrier was closed");
                    System.out.println("WON:" + lifecycle.claim(args[1],args[2]));System.out.flush();
                } else if ("claim-hold".equals(args[0])) {
                    assertTrue(lifecycle.claim(args[1], args[2]));
                    System.out.println("CLAIMED:" + ProcessHandle.current().pid()); System.out.flush();
                    System.in.read(); // Parent kills this process after the claim transaction has committed.
                } else if ("recover-tick".equals(args[0])) {
                    discoverWithRealWorker(child,args[1],args[2]);
                    System.out.println("RESUMED:" + ProcessHandle.current().pid()); System.out.flush();
                } else throw new IllegalArgumentException("Unknown isolated command");
            }
        }
    }
}
