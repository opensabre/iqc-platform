package io.github.opensabre.iqc.label;

import java.sql.DriverManager;
import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Opt-in migration verification against an identity-checked disposable local MySQL only. */
class IsolatedMysqlLabelBindingIT {
    private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true";
    private Connection server() throws Exception {
        var directory=System.getenv("IQC_MYSQL_VALIDATION_DIR");
        assertThat(directory).startsWith("/private/tmp/iqc-mysql-validation.");
        var c=DriverManager.getConnection(SERVER+OPTIONS,"root","");
        try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,@@datadir")) {
            assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(34307);assertThat(r.getString(2)).isEqualTo(directory+"/");
        } catch(Throwable failure){c.close();throw failure;}
        return c;
    }
    private Flyway flyway(String schema,String target) {
        var config=Flyway.configure().dataSource(SERVER+schema+OPTIONS,"root","").locations("classpath:db/migration/mysql").cleanDisabled(true);
        if(target!=null)config.target(target);return config.load();
    }
    private String create() throws Exception {
        var schema="iqc_p1_"+UUID.randomUUID().toString().replace("-","");
        try(var c=server();var s=c.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}return schema;
    }
    @Test void freshInstallAndUpgradePreserveBindingsAndEnforceTypeUniqueness() throws Exception {
        var schema=create();var fresh=flyway(schema,null);fresh.migrate();fresh.validate();assertThat(fresh.migrate().migrationsExecuted).isZero();
        var upgrade=create();flyway(upgrade,"1.1.34").migrate();
        try(var c=DriverManager.getConnection(SERVER+upgrade+OPTIONS,"root","");var s=c.createStatement()){
            seed(s,"r1","REGEX");seed(s,"r2","LLM");
            s.execute("INSERT INTO iqc_label_rule_binding (id,label_id,rule_id,rule_version_no) VALUES ('b1','t1','r1',1),('b2','t1','r2',1),('b3','t2','r1',1)");
        }
        var latest=flyway(upgrade,null);latest.migrate();latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(SERVER+upgrade+OPTIONS,"root","");var s=c.createStatement()){
            try(var rows=s.executeQuery("SELECT rule_type FROM iqc_label_rule_binding WHERE id='b1'")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("REGEX");}
            assertThatThrownBy(()->s.execute("INSERT INTO iqc_label_rule_binding(id,label_id,rule_id,rule_version_no,rule_type) VALUES ('bad','t1','other',1,'REGEX')")).hasMessageContaining("Duplicate");
            assertThatThrownBy(()->s.execute("INSERT INTO iqc_label_rule_binding(id,label_id,rule_id,rule_version_no) VALUES ('missing','t3','other',1)")).hasMessageContaining("Check constraint");
            s.execute("INSERT INTO iqc_label_action_definition(id,type,name) VALUES (9007199254740993,'RECORD_TAG','记录标签')");
            s.execute("INSERT INTO iqc_label_action_binding(id,label_id,action_id) VALUES (9007199254740994,'t1',9007199254740993)");
            assertThatThrownBy(()->s.execute("INSERT INTO iqc_label_action_binding(id,label_id,action_id) VALUES (9007199254740995,'t1',9007199254740993)")).hasMessageContaining("Duplicate");
            try(var row=s.executeQuery("SELECT action_id FROM iqc_label_action_binding")){assertThat(row.next()).isTrue();assertThat(row.getLong(1)).isEqualTo(9007199254740993L);}
        }
    }
    @Test void duplicateLegacyBindingsStopUpgradeWithoutDroppingOrChoosingRules() throws Exception {
        var schema=create();flyway(schema,"1.1.34").migrate();
        try(var c=DriverManager.getConnection(SERVER+schema+OPTIONS,"root","");var s=c.createStatement()){
            seed(s,"r1","REGEX");seed(s,"r2","REGEX");
            s.execute("INSERT INTO iqc_label_rule_binding(id,label_id,rule_id,rule_version_no) VALUES ('b1','t1','r1',1),('b2','t1','r2',1)");
        }
        assertThatThrownBy(()->flyway(schema,null).migrate()).hasMessageContaining("1.1.37");
        try(var c=DriverManager.getConnection(SERVER+schema+OPTIONS,"root","");var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM iqc_label_rule_binding")){
            assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(2);
        }
    }
    @Test void missingBoundVersionStopsUpgradeRatherThanUsingCurrentRule() throws Exception {
        var schema=create();flyway(schema,"1.1.34").migrate();
        try(var c=DriverManager.getConnection(SERVER+schema+OPTIONS,"root","");var s=c.createStatement()){
            s.execute("INSERT INTO iqc_label_rule_binding(id,label_id,rule_id,rule_version_no) VALUES ('b1','t1','missing',1)");
        }
        assertThatThrownBy(()->flyway(schema,null).migrate()).hasMessageContaining("1.1.37");
    }
    private void seed(java.sql.Statement s,String id,String type) throws Exception {
        s.execute("INSERT INTO iqc_quality_rule_version(id,rule_id,version_no,name,code,rule_type,status) VALUES ('v"+id+"','"+id+"',1,'test','"+id+"','"+type+"','PUBLISHED')");
    }
}
