package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.shared.IqcDataScope;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses real Spring transaction interception; JDBC connection is stubbed and no SQL is executed. */
class ExternalActionTransactionBoundaryTest {
    @Test void activeAnalysisTransactionRejectsBothSendAndLookupBeforePreparation() throws Exception {
        var dataSource=mock(DataSource.class);var connection=mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        var manager=new DataSourceTransactionManager(dataSource);
        var preparation=mock(ExternalActionPreparationService.class);
        var transport=mock(HttpExternalActionTransport.class);var scope=mock(IqcDataScope.class);
        var target=new ExternalActionAdapter(ActionExecutionContract.Type.WEBHOOK,preparation,
                new ExternalActionDestinations(List.of()),transport,scope);
        var interceptor=new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(interceptor);
        var adapter=(ExternalActionAdapter)factory.getProxy();
        new TransactionTemplate(manager).executeWithoutResult(status->{
            assertThrows(IllegalTransactionStateException.class,()->adapter.execute(null));
            assertThrows(IllegalTransactionStateException.class,()->adapter.reconcile(null));
        });
        verifyNoInteractions(preparation,transport,scope);
        verify(connection).commit();
    }
}
