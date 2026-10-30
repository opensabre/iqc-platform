package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.selector.SelectorDefinition;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;

import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static io.github.opensabre.iqc.result.IsolatedMysqlSelectorShardIT.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Opt-in million-row scan only: controlled actor, production service/mapper, isolated MySQL. */
class IsolatedMysqlMillionSelectorIT {
    static final int ROWS = 1_000_000;
    static final QueryTrace TRACE = new QueryTrace();

    @BeforeAll static void initialize() throws Exception {
        start();
        context.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration().addInterceptor(TRACE);
        System.out.println("MILLION environment java=" + System.getProperty("java.version")
                + " cpus=" + Runtime.getRuntime().availableProcessors()
                + " maxHeap=" + Runtime.getRuntime().maxMemory()
                + " mysql=" + jdbc.queryForObject("SELECT VERSION()", String.class));
        jdbc.queryForList("SHOW INDEX FROM iqc_conversation").forEach(row -> System.out.println("MILLION index " + row));
        String digits = "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String number = "a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n";
        long began = System.nanoTime();
        int inserted = jdbc.update("INSERT INTO iqc_conversation(id,source_file_name,status,message_count,created_by,owner_group_id,created_time,channel,source_fingerprint) "
                + "SELECT CONCAT('m',LPAD(" + number + ",7,'0')),'synthetic processed conversation','IMPORTED',1,'owner','group',?,"
                + "IF(" + number + ">=999863,'TAIL','SKIP'),SHA2(CONCAT('million:'," + number + "),256) FROM "
                + digits + " a CROSS JOIN " + digits + " b CROSS JOIN " + digits + " c CROSS JOIN " + digits + " d CROSS JOIN " + digits + " e CROSS JOIN " + digits + " f",
                Date.from(ANCHOR.minusSeconds(1)));
        assertThat(inserted).isEqualTo(ROWS);
        var seed = new IsolatedMysqlSelectorShardIT();
        seed.seed("z-owner", "owner", "other", ANCHOR.minusSeconds(1), "TAIL");
        seed.seed("z-group", "other", "group", ANCHOR.minusSeconds(1), "TAIL");
        seed.seed("z-foreign", "other", "other", ANCHOR.minusSeconds(1), "TAIL");
        seed.seed("z-open", "owner", "group", ANCHOR.truncatedTo(ChronoUnit.SECONDS), "TAIL");
        seed.seed("z-future", "owner", "group", ANCHOR.plusSeconds(1), "TAIL");
        jdbc.update("INSERT INTO iqc_conversation(id,source_file_name,status,message_count,created_by,owner_group_id,created_time,channel,source_fingerprint) VALUES ('z-excluded','synthetic','FAILED',1,'owner','group',?,'TAIL',SHA2('excluded',256))", Date.from(ANCHOR.minusSeconds(1)));
        System.out.println("MILLION seed rows=" + inserted + " controls=6 elapsedMs=" + (System.nanoTime()-began)/1_000_000);
    }

    @AfterAll static void shutdown() { close(); }

    @Test void millionCandidatesAcrossOwnerGroupAllAndLongUnmatchedPrefix() throws Exception {
        for (String mode : List.of("owner", "group", "all", "tail")) {
            when(scope.canViewAll()).thenReturn(mode.equals("all"));
            when(scope.owner()).thenReturn(mode.equals("group") ? "no-owner" : "owner");
            when(scope.groupId()).thenReturn(mode.equals("owner") ? null : "group");
            String authorization = mode.equals("all") ? "1=1" : mode.equals("owner") ? "created_by='owner'"
                    : mode.equals("group") ? "(created_by='no-owner' OR owner_group_id='group')" : "(created_by='owner' OR owner_group_id='group')";
            List<String> expected = jdbc.queryForList("SELECT id FROM iqc_conversation WHERE status='IMPORTED' AND created_time < ? AND " + authorization + " ORDER BY id", String.class, Date.from(ANCHOR.truncatedTo(ChronoUnit.SECONDS)));
            assertThat(expected.size()).isGreaterThanOrEqualTo(ROWS);
            var condition = mode.equals("tail") ? new SelectorDefinition.Condition(null, null, "channel", SelectorDefinition.Operator.EQ, new ObjectMapper().valueToTree("TAIL")) : null;
            var definition = new IsolatedMysqlSelectorShardIT().definition(condition);
            TRACE.begin(mode);
            String cursor = null;
            int scanned = 0, selected = 0, pages = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long began = System.nanoTime();
            while (true) {
                var page = service.scanAllPageAt(definition, ANCHOR, cursor, 1000);
                pages++;
                assertThat(pages).isLessThanOrEqualTo(1002);
                assertThat(page.afterId()).isEqualTo(cursor);
                assertThat(page.createdBeforeExclusive()).isEqualTo(ANCHOR.truncatedTo(ChronoUnit.SECONDS));
                assertThat(page.scannedCount()).isBetween(1, 1000);
                int end = Math.min(expected.size(), scanned + 1000);
                List<String> candidates = expected.subList(scanned, end);
                assertThat(page.nextAfterId()).isEqualTo(candidates.getLast());
                List<String> wanted = mode.equals("tail") ? candidates.stream().filter(id -> id.startsWith("z-") || id.compareTo("m0999863") >= 0).toList() : candidates;
                assertThat(page.selection().candidateCount()).isEqualTo(candidates.size());
                assertThat(page.selection().limited()).isFalse();
                assertThat(page.selection().selectedConversationIds()).containsExactlyElementsOf(wanted);
                for (String id : wanted) digest.update((id + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                scanned += page.scannedCount();
                selected += wanted.size();
                cursor = page.nextAfterId();
                assertThat(page.hasMore()).isEqualTo(scanned < expected.size());
                if (pages % 100 == 0) System.out.println("MILLION progress mode=" + mode + " pages=" + pages + " scanned=" + scanned + " selected=" + selected + " elapsedMs=" + (System.nanoTime()-began)/1_000_000);
                if (!page.hasMore()) break;
            }
            assertThat(scanned).isEqualTo(expected.size());
            var empty = service.scanAllPageAt(definition, ANCHOR, cursor, 1000);
            assertThat(empty.scannedCount()).isZero();
            assertThat(empty.selection()).isNull();
            assertThat(empty.hasMore()).isFalse();
            TRACE.finish();
            System.out.println("MILLION result mode=" + mode + " pages=" + pages + " scanned=" + scanned + " selected=" + selected + " sha256=" + HexFormat.of().formatHex(digest.digest()) + " elapsedMs=" + (System.nanoTime()-began)/1_000_000);
        }
    }

    @Intercepts(@Signature(type=Executor.class, method="query", args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class QueryTrace implements Interceptor {
        String mode;
        final Map<String,List<Long>> times = new LinkedHashMap<>();
        void begin(String value) { mode=value; times.clear(); }
        void finish() {
            times.forEach((kind,values) -> { var sorted=values.stream().sorted().toList();
                System.out.println("MILLION query mode="+mode+" kind="+kind+" calls="+sorted.size()+" totalMs="+sorted.stream().mapToLong(Long::longValue).sum()/1_000_000+" p95Ms="+sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1_000_000+" maxMs="+sorted.getLast()/1_000_000);
            });
            mode=null;
        }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if(mode==null) return invocation.proceed();
            var statement=(MappedStatement)invocation.getArgs()[0];
            var parameter=invocation.getArgs()[1];
            var bound=statement.getBoundSql(parameter);
            String sql=bound.getSql();
            String kind=sql.contains("IS NULL") ? "undated" : sql.contains("LIMIT 1001") ? "keyset" : statement.getId();
            var samples=times.computeIfAbsent(kind,ignored->new ArrayList<>());
            int ordinal=samples.size();
            if((kind.equals("undated")||kind.equals("keyset")) && (ordinal==0||ordinal==500||ordinal==999)) {
                System.out.println("MILLION plan mode="+mode+" kind="+kind+" page="+(ordinal+1)+" sql="+sql);
                var meta=statement.getConfiguration().newMetaObject(parameter);
                for(var mapping:bound.getParameterMappings()) {
                    Object value=bound.hasAdditionalParameter(mapping.getProperty())?bound.getAdditionalParameter(mapping.getProperty()):meta.getValue(mapping.getProperty());
                    System.out.println("MILLION parameter "+mapping.getProperty()+" value="+value+" javaType="+(value==null?"null":value.getClass().getName())+" jdbcType="+mapping.getJdbcType());
                }
                try(var connection=jdbc.getDataSource().getConnection();var explain=connection.prepareStatement("EXPLAIN "+sql)) {
                    new DefaultParameterHandler(statement,parameter,bound).setParameters(explain);
                    try(var rows=explain.executeQuery()) { while(rows.next()) { var plan=new LinkedHashMap<String,Object>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)plan.put(rows.getMetaData().getColumnLabel(i),rows.getObject(i));System.out.println("MILLION explain "+plan); } }
                }
            }
            long began=System.nanoTime();
            try { return invocation.proceed(); } finally { samples.add(System.nanoTime()-began); }
        }
    }
}
