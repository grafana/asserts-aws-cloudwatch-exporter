/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The exporter can either function in single-tenant or multi-tenant mode. In addition, the exporter can also function
 * in distributed mode where the AWS Accounts to be processed can be sharded across multiple instances of the exporter.
 * The table below lists out the options of installing/configuring the exporter. The exporter can be deployed in the
 * following modes
 * <table border="2">
 *     <tr>
 *         <td><b>Saas/OnPremise</b></td>
 *         <td><b>Container Platform</b></td>
 *         <td><b>Installation</b></td>
 *         <td><b>Tenancy Mode</b></td>
 *         <td><b>Processing Mode</b></td>
 *         <td><b>ExporterConfig/AWS Accounts Information Retrieval</b></td>
 *     </tr>
 *     <tr>
 *         <td>SaaS</td>
 *         <td>Asserts Internal</td>
 *         <td>Asserts Internal</td>
 *         <td>Multi-tenant</td>
 *         <td>Distributed</td>
 *         <td>Asserts Internal</td>
 *     </tr>
 *     <tr>
 *         <td>OnPremise</td>
 *         <td>Docker</td>
 *         <td>Asserts Self-hosted distribution</td>
 *         <td>Multi-Tenant</td>
 *         <td>Distributed</td>
 *         <td>Asserts Internal</td>
 *     </tr>
 *     <tr>
 *         <td>OnPremise</td>
 *         <td>K8s</td>
 *         <td>Asserts Helm Chart</td>
 *         <td>Multi-Tenant</td>
 *         <td>Distributed</td>
 *         <td>Asserts Internal</td>
 *     </tr>
 *     <tr>
 *         <td>OnPremise</td>
 *         <td>AWS/ECS</td>
 *         <td>CloudFormation Template</td>
 *         <td>Single Tenant</td>
 *         <td>All accounts processed by one instance</td>
 *         <td>From Asserts API Server</td>
 *     </tr>
 *     <tr>
 *         <td>OnPremise</td>
 *         <td>AWS/ECS</td>
 *         <td>CloudFormation Template</td>
 *         <td>Single Tenant</td>
 *         <td>Distributed (Recommended for large number of accounts)</td>
 *         <td>From Asserts API Server</td>
 *     </tr>
 * </table>
 */
@Component
public class EnvironmentConfig {
    private final boolean enabled;
    private final String tenantMode;
    private final String deploymentMode;

    public EnvironmentConfig(@Value("${aws_exporter.enabled:true}") String enabled,
                             @Value("${aws_exporter.tenant_mode:single}") String tenantMode,
                             @Value("${aws_exporter.deployment_mode:single-tenant-single-instance}") String deploymentMode) {
        this.enabled =
                "true".equalsIgnoreCase(enabled) || "yes".equalsIgnoreCase(enabled) || "y".equalsIgnoreCase(
                        enabled);
        this.tenantMode = tenantMode;
        this.deploymentMode = deploymentMode;
    }

    public boolean isDisabled() {
        return !enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSingleTenant() {
        return "single".equals(tenantMode);
    }

    public boolean isMultiTenant() {
        return !isSingleTenant();
    }

    public boolean isDistributed() {
        return deploymentMode.contains("distributed");
    }

    public boolean isSingleInstance() {
        return !isDistributed();
    }
}
