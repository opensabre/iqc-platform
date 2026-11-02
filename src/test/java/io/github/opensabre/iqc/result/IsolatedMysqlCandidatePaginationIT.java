package io.github.opensabre.iqc.result;

import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.quality.dao.QualitySampleMapper;
import io.github.opensabre.iqc.shared.IqcDataScope;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real MySQL pagination using existing guarded migrations, mapper and pagination interceptor. */
class IsolatedMysqlCandidatePaginationIT {
    @Test void authorizationKeywordCountsAndStablePagesUseTheSameSqlScope() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        try(var context=new AnnotationConfigApplicationContext(IsolatedMysqlMeasuredPublicationIT.Config.class)) {
            var db=new JdbcTemplate(context.getBean(DataSource.class));
            var scope=context.getBean(IqcDataScope.class);
            when(scope.owner()).thenReturn("alice");when(scope.groupId()).thenReturn("team");when(scope.canViewAll()).thenReturn(false);
            var mapper=context.getBean(SqlSessionTemplate.class).getMapper(QualitySampleMapper.class);
            var service=new QualityOperationsService(null,null,mapper,null,null,null,scope);
            for(int i=0;i<5;i++) db.update("INSERT INTO iqc_quality_sample(id,name,sample_type,status,created_by,owner_group_id,content_snapshot,created_time) VALUES (?,?,'UNCLASSIFIED','CANDIDATE',?,?,'processed conversation','2026-01-01 00:00:00')",
                    "page-"+i,"needle "+i,i==0?"alice":"other",i<3?"team":"foreign");
            var first=service.samples("unclassified","candidate",1,2,"needle");
            var second=service.samples("unclassified","candidate",2,2,"needle");
            assertThat(first.total()).isEqualTo(3);assertThat(second.total()).isEqualTo(3);
            assertThat(first.records()).extracting(r->r.getId()).containsExactly("page-0","page-1");
            assertThat(second.records()).extracting(r->r.getId()).containsExactly("page-2");
            assertThat(service.samples(null,null,1,20,"page-4").total()).isZero();
            assertThat(service.samples(null,"DISABLED",1,20,"needle").total()).isZero();
            when(scope.groupId()).thenReturn(null);
            assertThat(service.samples(null,null,1,20,"needle").records()).extracting(r->r.getId()).containsExactly("page-0");
            when(scope.canViewAll()).thenReturn(true);
            assertThat(service.samples(null,null,1,20,"needle").total()).isEqualTo(5);
            assertThat(service.samples(null,null,1,20,"page-4").records()).extracting(r->r.getId()).containsExactly("page-4");
            assertThat(service.samples(null,null,4,2,"needle").records()).isEmpty();
            assertThat(service.samples(null,null,4,2,"needle").total()).isEqualTo(5);
        } finally {IsolatedMysqlTaskExecutionStateIT.close();}
    }
}
