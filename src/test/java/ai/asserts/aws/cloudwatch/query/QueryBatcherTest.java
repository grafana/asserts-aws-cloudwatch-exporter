/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.query;

import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryBatcherTest extends EasyMockSupport {
    @Test
    void noSplit() {
        QueryBatcher queryBatcher = new QueryBatcher(5, 10);
        MetricQuery metricQuery = mock(MetricQuery.class);
        List<MetricQuery> queries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            queries.add(metricQuery);
        }
        expect(metricQuery.getExpectedSamples()).andReturn(1).anyTimes();
        replayAll();
        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        assertEquals(1, batches.size());
        assertEquals(ImmutableList.of(metricQuery, metricQuery, metricQuery), batches.get(0));
        verifyAll();
    }

    @Test
    void split_dueTo_queryLimit() {
        QueryBatcher queryBatcher = new QueryBatcher(2, 10);
        MetricQuery metricQuery = mock(MetricQuery.class);
        List<MetricQuery> queries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            queries.add(metricQuery);
        }
        expect(metricQuery.getExpectedSamples()).andReturn(1).anyTimes();
        replayAll();
        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        assertEquals(2, batches.size());
        assertEquals(ImmutableList.of(metricQuery, metricQuery), batches.get(0));
        assertEquals(ImmutableList.of(metricQuery), batches.get(1));
        verifyAll();
    }

    @Test
    void split_dueTo_dataLimit() {
        QueryBatcher queryBatcher = new QueryBatcher(5, 10);
        MetricQuery metricQuery = mock(MetricQuery.class);
        List<MetricQuery> queries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            queries.add(metricQuery);
        }
        expect(metricQuery.getExpectedSamples()).andReturn(4).anyTimes();
        replayAll();
        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        assertEquals(2, batches.size());
        assertEquals(ImmutableList.of(metricQuery, metricQuery), batches.get(0));
        assertEquals(ImmutableList.of(metricQuery), batches.get(1));
        verifyAll();
    }
}
