
package ai.asserts.aws.cloudwatch.query;

import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryBatcherTest extends EasyMockSupport {
    private MetricQuery metricQuery;

    @BeforeEach
    public void setup() {
        metricQuery = mock(MetricQuery.class);
    }

    @Test
    void noSplit() {
        QueryBatcher queryBatcher = new QueryBatcher(5);
        List<MetricQuery> queries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            queries.add(metricQuery);
        }
        replayAll();
        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        assertEquals(1, batches.size());
        assertEquals(ImmutableList.of(metricQuery, metricQuery, metricQuery), batches.get(0));
        verifyAll();
    }

    @Test
    void split() {
        QueryBatcher queryBatcher = new QueryBatcher(2);
        List<MetricQuery> queries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            queries.add(metricQuery);
        }
        replayAll();
        List<List<MetricQuery>> batches = queryBatcher.splitIntoBatches(queries);
        assertEquals(2, batches.size());
        assertEquals(ImmutableList.of(metricQuery, metricQuery), batches.get(0));
        assertEquals(ImmutableList.of(metricQuery), batches.get(1));
        verifyAll();
    }
}
