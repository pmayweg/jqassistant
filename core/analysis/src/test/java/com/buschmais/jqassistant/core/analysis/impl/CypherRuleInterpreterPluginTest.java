package com.buschmais.jqassistant.core.analysis.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.buschmais.jqassistant.core.analysis.api.AnalyzerContext;
import com.buschmais.jqassistant.core.report.api.ReportHelper;
import com.buschmais.jqassistant.core.report.api.model.Result;
import com.buschmais.jqassistant.core.report.api.model.Row;
import com.buschmais.jqassistant.core.report.api.model.VerificationResult;
import com.buschmais.jqassistant.core.rule.api.model.Constraint;
import com.buschmais.jqassistant.core.rule.api.model.Report;
import com.buschmais.jqassistant.core.rule.api.model.RuleException;
import com.buschmais.jqassistant.core.shared.transaction.Transactional;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.xo.api.Query;
import com.buschmais.xo.api.Query.Result.CompositeRowObject;
import com.buschmais.xo.api.ResultIterator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.buschmais.jqassistant.core.rule.api.model.Severity.MAJOR;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CypherRuleInterpreterPluginTest {

    private static final String CONSTRAINT_ID = "constraint";
    public static final String PRIMARY_COLUMN = "primary";
    public static final String SECONDARY_COLUMN = "secondary";

    private final CypherRuleInterpreterPlugin interpreterPlugin = new CypherRuleInterpreterPlugin();

    @Mock
    private AnalyzerContext analyzerContext;

    @Mock
    private Store store;

    @BeforeEach
    void beforeEach() {
        doReturn(store).when(analyzerContext)
            .getStore();
        doAnswer(invocation -> ((Transactional.TransactionalSupplier<?, ?>) invocation.getArgument(0)).execute()).when(store)
            .requireTransaction(any(Transactional.TransactionalSupplier.class));
        doAnswer(invocation -> ReportHelper.toRow(invocation.getArgument(0), invocation.getArgument(1))).when(analyzerContext)
            .toRow(any(), anyMap());
        doAnswer(invocation -> ReportHelper.toColumn(invocation.getArgument(0))).when(analyzerContext)
            .toColumn(any());
    }

    @Test
    void rows() throws RuleException {
        Constraint constraint = prepareConstraint(Map.of(PRIMARY_COLUMN, "value1_1", SECONDARY_COLUMN, "value1_2"),
            Map.of(PRIMARY_COLUMN, "value2_1", SECONDARY_COLUMN, "value2_2"));
        doReturn(VerificationResult.builder()
            .success(true)
            .rowCount(2)
            .build()).when(analyzerContext)
            .verify(any(Constraint.class), anyList(), anyList());

        Result<Constraint> result = interpreterPlugin.execute("MATCH n RETURN n", constraint, emptyMap(), MAJOR, analyzerContext);

        assertThat(result.getRows()).hasSize(2);
        verify(analyzerContext, times(2)).isSuppressed(eq(constraint), eq(PRIMARY_COLUMN), any(Row.class));
    }

    private Constraint prepareConstraint(Map<String, Object>... resultRows) {
        Report report = Report.builder()
            .primaryColumn(PRIMARY_COLUMN)
            .build();
        Constraint constraint = Constraint.builder()
            .id(CONSTRAINT_ID)
            .report(report)
            .build();
        ResultIterator<CompositeRowObject> resultIterator = asResultIterator(stream(resultRows).map(this::asRow)
            .collect(toList()));
        Query.Result<CompositeRowObject> queryResult = mock(Query.Result.class);
        doReturn(resultIterator).when(queryResult)
            .iterator();
        doReturn(queryResult).when(store)
            .executeQuery(anyString(), anyMap());

        return constraint;
    }

    private static ResultIterator<CompositeRowObject> asResultIterator(List<CompositeRowObject> queryRows) {
        Iterator<CompositeRowObject> iterator = queryRows.iterator();
        return new ResultIterator<>() {

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public CompositeRowObject next() {
                return iterator.next();
            }

            @Override
            public void close() {
            }
        };
    }

    private CompositeRowObject asRow(Map<String, Object> row) {
        return new CompositeRowObject() {
            @Override
            public List<String> getColumns() {
                return new ArrayList<>(row.keySet());
            }

            @Override
            public <C> C get(String column, Class<C> columnType) {
                return columnType.cast(row.get(column));
            }

            @Override
            public <I> I getId() {
                return (I) Integer.valueOf(row.hashCode());
            }

            @Override
            public <T> T as(Class<T> aClass) {
                return aClass.cast(row);
            }

            @Override
            public <D> D getDelegate() {
                return (D) row;
            }
        };
    }

}
