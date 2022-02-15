/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MetricsRequest {
    private String requestId;
    private long timestamp;
    private List<MetricRecord> records;
}
