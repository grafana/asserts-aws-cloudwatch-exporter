
package ai.asserts.aws;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;

@Component
@AllArgsConstructor
public class AWSClientProvider {

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
}
