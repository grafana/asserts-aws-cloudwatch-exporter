/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@Component
@AllArgsConstructor
@SuppressWarnings("unused")
public class AWSClientProvider {

    public CloudWatchClient getCloudWatchClient(String region) {
        return CloudWatchClient.builder()
                .region(Region.of(region)).build();
    }

    public CloudWatchLogsClient getCloudWatchLogsClient(String region) {
        return CloudWatchLogsClient.builder()
                .region(Region.of(region)).build();
    }

    public LambdaClient getLambdaClient(String region) {
        return LambdaClient.builder()
                .region(Region.of(region)).build();
    }

    public SqsClient getSqsClient(String region) {
        return SqsClient.builder()
                .region(Region.of(region)).build();
    }

    public DynamoDbClient getDynamoDBClient(String region) {
        return DynamoDbClient.builder()
                .region(Region.of(region)).build();
    }

    public S3Client getS3Client(String region) {
        return S3Client.builder()
                .region(Region.of(region)).build();
    }
}
