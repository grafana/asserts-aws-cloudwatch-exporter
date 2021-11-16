
package ai.asserts.aws.cloudwatch.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class QueryBatcher {
    private final int queryCountLimit;
    private final int dataLimit;

    public QueryBatcher(@Value("${aws.metric.data.query.count.limit:500}") int queryCountLimit,
                        @Value("${aws.metric.data.limit:100800}") int dataLimit) {
        this.queryCountLimit = queryCountLimit;
        this.dataLimit = dataLimit;
    }

    public List<List<MetricQuery>> splitIntoBatches(List<MetricQuery> queries) {
        // Split queries into batches to meet the following limits
        // Max of 500 metrics per API call
        // Max of 100,800 result samples per API call
        List<List<MetricQuery>> batches = new ArrayList<>();
        batches.add(new ArrayList<>());
        queries.forEach(metricQuery -> {
            List<MetricQuery> currentBatch = batches.get(batches.size() - 1);
            int sumTillNow = currentBatch.stream().mapToInt(mQ -> mQ.getMetricConfig().numSamplesPerScrape()).sum();
            if (sumTillNow + metricQuery.getMetricConfig().numSamplesPerScrape() < dataLimit &&
                    currentBatch.size() < queryCountLimit) {
                currentBatch.add(metricQuery);
            } else {
                currentBatch = new ArrayList<>();
                currentBatch.add(metricQuery);
                batches.add(currentBatch);
            }
        });
        return batches;
    }
}
