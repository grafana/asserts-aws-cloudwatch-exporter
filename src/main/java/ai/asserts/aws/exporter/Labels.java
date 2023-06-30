/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.HashMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.TENANT;
import static io.micrometer.core.instrument.util.StringUtils.isNotEmpty;

@Getter
@Builder
@ToString
@EqualsAndHashCode(callSuper = true)
public class Labels extends TreeMap<String, String> {
    @JsonProperty("__metrics_path__")
    private String metricsPath;
    private String workload;
    private String job;
    @JsonProperty("cluster")
    private String cluster;
    @JsonProperty("ecs_taskdef_name")
    private String taskDefName;
    @JsonProperty("ecs_taskdef_version")
    private String taskDefVersion;
    @JsonProperty
    private String container;
    @JsonProperty("task_id")
    private String taskId;
    @JsonProperty("pod")
    private String pod;

    private String vpcId;
    private String subnetId;
    @JsonProperty("availability_zone")
    private String availabilityZone;
    @JsonProperty("namespace")
    @Builder.Default
    private String namespace = "AWS/ECS";
    @JsonProperty("region")
    private String region;
    @JsonProperty("account_id")
    private String accountId;
    @JsonProperty("asserts_env")
    private String env;
    @JsonProperty("asserts_site")
    private String site;

    private String tenant;

    public void populateMapEntries() {
        if (metricsPath != null) {
            put("__metrics_path__", metricsPath);
        }
        if (workload != null) {
            put("workload", workload);
            put("service", workload);
        }
        if (job != null) {
            put("job", job);
        }
        if (cluster != null) {
            put("cluster", cluster);
        }
        if (taskDefName != null) {
            put("ecs_taskdef_name", taskDefName);
        }
        if (taskDefVersion != null) {
            put("ecs_taskdef_version", taskDefVersion);
        }
        if (container != null) {
            put("container", container);
        }
        if (taskId != null) {
            put("task_id", taskId);
        }
        if (pod != null) {
            put("pod", pod);
        }
        if (isNotEmpty(vpcId)) {
            put("vpc_id", vpcId);
        }
        if (isNotEmpty(subnetId)) {
            put("subnet_id", subnetId);
        }
        if (availabilityZone != null) {
            put("availability_zone", availabilityZone);
        }
        put("namespace", "AWS/ECS");
        put("region", region);
        put("account_id", accountId);
        if (env != null) {
            put("asserts_env", env);
        }
        if (site != null) {
            put("asserts_site", site);
        }
        if (tenant != null) {
            put(TENANT, tenant);
        }
    }
}
