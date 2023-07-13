
package ai.asserts.aws.cloudwatch.query;

import com.google.common.collect.Lists;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryBatcher {
    private final int queryCountLimit;

    public QueryBatcher(@Value("${aws_exporter.metric_data_query_limit:500}") int queryCountLimit) {
        this.queryCountLimit = queryCountLimit;
    }

    public List<List<MetricQuery>> splitIntoBatches(List<MetricQuery> queries) {
        // Split queries into batches to meet the following limits
        // Max of 500 metrics per API call
        return Lists.partition(queries, queryCountLimit);
    }
}
