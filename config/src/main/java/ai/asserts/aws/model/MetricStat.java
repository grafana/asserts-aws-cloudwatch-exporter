
package ai.asserts.aws.model;

import lombok.Getter;

@Getter
public enum MetricStat {
    Sum("sum"),
    Average("avg"),
    SampleCount("count"),
    Minimum("min"),
    Maximum("max"),
    p95("p95"),
    p99("p99"),
    p50("p50");

    private String shortForm;

    MetricStat(String shortForm) {
        this.shortForm = shortForm;
    }
}
