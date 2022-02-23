
package ai.asserts.aws;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.sts.StsClient;

@Component
@AllArgsConstructor
public class AWSClientProvider {
    public AutoScalingClient getAutoScalingClient(String region) {
        return AutoScalingClient.builder().region(Region.of(region)).build();
    }

    public ApiGatewayClient getApiGatewayClient(String region) {
        return ApiGatewayClient.builder().region(Region.of(region)).build();
    }

    public ElasticLoadBalancingV2Client getELBV2Client(String region) {
        return ElasticLoadBalancingV2Client.builder().region(Region.of(region)).build();
    }

    public ElasticLoadBalancingClient getELBClient(String region) {
        return ElasticLoadBalancingClient.builder().region(Region.of(region)).build();
    }

    public CloudWatchClient getCloudWatchClient(String region) {
        return CloudWatchClient.builder().region(Region.of(region)).build();
    }

    public CloudWatchLogsClient getCloudWatchLogsClient(String region) {
        return CloudWatchLogsClient.builder().region(Region.of(region)).build();
    }

    public LambdaClient getLambdaClient(String region) {
        return LambdaClient.builder().region(Region.of(region)).build();
    }

    public ResourceGroupsTaggingApiClient getResourceTagClient(String region) {
        return ResourceGroupsTaggingApiClient.builder().region(Region.of(region)).build();
    }

    public EcsClient getECSClient(String region) {
        return EcsClient.builder().region(Region.of(region)).build();
    }

    public ConfigClient getConfigClient(String region) {
        return ConfigClient.builder().region(Region.of(region)).build();
    }

    public StsClient getStsClient(String region) {
        return StsClient.builder().region(Region.of(region)).build();
    }

    public Ec2Client getEc2Client(String region) {
        return Ec2Client.builder().region(Region.of(region)).build();
    }
}
