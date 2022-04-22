package ai.asserts.aws;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
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
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2Client;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sts.StsClient;

import java.util.Optional;

@Component
@AllArgsConstructor
public class AWSClientProvider {
    private final AWSSessionProvider awsSessionProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;

    public SecretsManagerClient getSecretsManagerClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return sessionConfig.map(config ->
                SecretsManagerClient.builder()
                        .credentialsProvider(() -> AwsSessionCredentials.create(
                                config.getAccessKeyId(), config.getSecretAccessKey(),
                                config.getSessionToken()))
                        .region(Region.of(region)).build())
                .orElse(SecretsManagerClient.builder().region(Region.of(region)).build());
    }

    public AutoScalingClient getAutoScalingClient(String region, String assumeRole) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region, assumeRole);
        return
                sessionConfig.map(config ->
                        AutoScalingClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(AutoScalingClient.builder().region(Region.of(region)).build());
    }

    public ApiGatewayClient getApiGatewayClient(String region, String assumeRole) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region, assumeRole);
        return
                sessionConfig.map(config ->
                        ApiGatewayClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(ApiGatewayClient.builder().region(Region.of(region)).build());
    }

    public ElasticLoadBalancingV2Client getELBV2Client(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        ElasticLoadBalancingV2Client.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(ElasticLoadBalancingV2Client.builder().region(Region.of(region)).build());
    }

    public ElasticLoadBalancingClient getELBClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config -> ElasticLoadBalancingClient.builder()
                        .credentialsProvider(() -> AwsSessionCredentials.create(
                                config.getAccessKeyId(), config.getSecretAccessKey(),
                                config.getSessionToken()))
                        .region(Region.of(region)).build())
                        .orElse(ElasticLoadBalancingClient.builder().region(Region.of(region)).build());
    }

    public CloudWatchClient getCloudWatchClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        CloudWatchClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(CloudWatchClient.builder().region(Region.of(region)).build());
    }

    public CloudWatchLogsClient getCloudWatchLogsClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        CloudWatchLogsClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(CloudWatchLogsClient.builder().region(Region.of(region)).build());
    }

    public LambdaClient getLambdaClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        LambdaClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(LambdaClient.builder().region(Region.of(region)).build());
    }

    public ResourceGroupsTaggingApiClient getResourceTagClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        ResourceGroupsTaggingApiClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(ResourceGroupsTaggingApiClient.builder().region(Region.of(region)).build());
    }

    public EcsClient getECSClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        EcsClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(EcsClient.builder().region(Region.of(region)).build());
    }

    public ConfigClient getConfigClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return sessionConfig.map(config -> ConfigClient.builder()
                .credentialsProvider(() -> AwsSessionCredentials.create(
                        config.getAccessKeyId(), config.getSecretAccessKey(),
                        config.getSessionToken()))
                .region(Region.of(region)).build())
                .orElse(ConfigClient.builder().region(Region.of(region)).build());
    }

    public StsClient getStsClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        StsClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(StsClient.builder().region(Region.of(region)).build());
    }

    public Ec2Client getEc2Client(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        Ec2Client.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(Ec2Client.builder().region(Region.of(region)).build());
    }

    public KinesisAnalyticsV2Client getKAClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        KinesisAnalyticsV2Client.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build())
                        .orElse(KinesisAnalyticsV2Client.builder().region(Region.of(region)).build());
    }

    public FirehoseClient getFirehoseClient(String region) {
        Optional<AWSSessionConfig> sessionConfig = awsSessionProvider.getSessionCredential(region,
                scrapeConfigProvider.getScrapeConfig().getAssumeRole());
        return
                sessionConfig.map(config ->
                        FirehoseClient.builder()
                                .credentialsProvider(() -> AwsSessionCredentials.create(
                                        config.getAccessKeyId(), config.getSecretAccessKey(),
                                        config.getSessionToken()))
                                .region(Region.of(region)).build()
                )
                        .orElse(FirehoseClient.builder().region(Region.of(region)).build());
    }
}
