package ai.asserts.aws;

import ai.asserts.aws.AccountProvider.AWSAccount;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClientBuilder;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.AutoScalingClientBuilder;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.ConfigClientBuilder;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.EcsClientBuilder;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClientBuilder;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2ClientBuilder;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.FirehoseClientBuilder;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2Client;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2ClientBuilder;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.LambdaClientBuilder;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.util.StringUtils.hasLength;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Component
@AllArgsConstructor
public class AWSClientProvider {
    private final Map<AccountRegion, AWSSessionConfig> credentialCache = new ConcurrentHashMap<>();

    public StsClient getStsClient(String region) {
        return StsClient.builder()
                .region(Region.of(region))
                .build();
    }

    public SecretsManagerClient getSecretsManagerClient(String region) {
        return SecretsManagerClient.builder()
                .region(Region.of(region))
                .build();
    }

    public SqsClient getSqsClient(String region, AWSAccount account) {
        SqsClientBuilder clientBuilder = SqsClient.builder().region(Region.of(region));
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public AutoScalingClient getAutoScalingClient(String region, AWSAccount account) {
        AutoScalingClientBuilder clientBuilder = AutoScalingClient.builder().region(Region.of(region));
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public ApiGatewayClient getApiGatewayClient(String region, AWSAccount account) {
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        ApiGatewayClientBuilder clientBuilder = ApiGatewayClient.builder().region(Region.of(region));
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public ElasticLoadBalancingV2Client getELBV2Client(String region, AWSAccount account) {
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        ElasticLoadBalancingV2ClientBuilder clientBuilder = ElasticLoadBalancingV2Client.builder().region(Region.of(region));
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public ElasticLoadBalancingClient getELBClient(String region, AWSAccount account) {
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        ElasticLoadBalancingClientBuilder clientBuilder = ElasticLoadBalancingClient.builder().region(Region.of(region));
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public CloudWatchClient getCloudWatchClient(String region, AWSAccount account) {
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        CloudWatchClientBuilder clientBuilder = CloudWatchClient.builder().region(Region.of(region));
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public CloudWatchLogsClient getCloudWatchLogsClient(String region, AWSAccount account) {
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        CloudWatchLogsClientBuilder clientBuilder = CloudWatchLogsClient.builder().region(Region.of(region));
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public LambdaClient getLambdaClient(String region, AWSAccount account) {
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        LambdaClientBuilder clientBuilder = LambdaClient.builder().region(Region.of(region));
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public ResourceGroupsTaggingApiClient getResourceTagClient(String region, AWSAccount account) {
        ResourceGroupsTaggingApiClientBuilder clientBuilder = ResourceGroupsTaggingApiClient.builder()
                .region(Region.of(region));

        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public EcsClient getECSClient(String region, AWSAccount account) {
        EcsClientBuilder clientBuilder = EcsClient.builder()
                .region(Region.of(region));

        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public ConfigClient getConfigClient(String region, AWSAccount account) {
        ConfigClientBuilder clientBuilder = ConfigClient.builder()
                .region(Region.of(region));

        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public Ec2Client getEc2Client(String region, AWSAccount account) {
        Ec2ClientBuilder clientBuilder = Ec2Client.builder().region(Region.of(region));
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public KinesisAnalyticsV2Client getKAClient(String region, AWSAccount account) {
        KinesisAnalyticsV2ClientBuilder clientBuilder = KinesisAnalyticsV2Client.builder()
                .region(Region.of(region));
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public FirehoseClient getFirehoseClient(String region, AWSAccount account) {
        FirehoseClientBuilder clientBuilder = FirehoseClient.builder().region(Region.of(region));
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    public S3Client getS3Client(String region, AWSAccount account) {
        S3ClientBuilder clientBuilder = S3Client.builder().region(Region.of(region));
        Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
        if (credentialsOpt.isPresent()) {
            clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
        }
        if (account.getAssumeRole() != null) {
            clientBuilder = clientBuilder.credentialsProvider(() ->
                    getAwsSessionCredentials(region, account, credentialsOpt));
        }
        return clientBuilder.build();
    }

    private AwsSessionCredentials getAwsSessionCredentials(String region, AWSAccount account,
                                                           Optional<AwsCredentialsProvider> credentialsOpt) {
        AccountRegion key = new AccountRegion(account, region);
        AWSSessionConfig credentials = credentialCache.get(key);
        if (credentials == null || credentials.getExpiring().compareTo(Instant.now()) <= 0) {
            StsClientBuilder stsClientBuilder = StsClient.builder().region(Region.of(region));
            if (credentialsOpt.isPresent()) {
                stsClientBuilder = stsClientBuilder.credentialsProvider(credentialsOpt.get());
            }
            AssumeRoleResponse response = stsClientBuilder.build().assumeRole(AssumeRoleRequest.builder()
                    .roleSessionName("session1")
                    .roleArn(account.getAssumeRole())
                    .build());
            credentials = AWSSessionConfig.builder()
                    .accessKeyId(response.credentials().accessKeyId())
                    .secretAccessKey(response.credentials().secretAccessKey())
                    .sessionToken(response.credentials().sessionToken())
                    .expiring(response.credentials().expiration())
                    .build();
            credentialCache.put(key, credentials);
        }
        return AwsSessionCredentials.create(
                credentials.getAccessKeyId(),
                credentials.getSecretAccessKey(),
                credentials.getSessionToken());
    }

    private Optional<AwsCredentialsProvider> getCredentialsProvider(AWSAccount authConfig) {
        if (hasLength(authConfig.getAccessId()) && hasLength(authConfig.getSecretKey())) {
            return Optional.of(StaticCredentialsProvider
                    .create(AwsBasicCredentials.create(authConfig.getAccessId(), authConfig.getSecretKey())));
        }
        return Optional.empty();
    }

    @EqualsAndHashCode
    @Getter
    @AllArgsConstructor
    public static class AccountRegion {
        private final AWSAccount account;
        private final String region;
    }
}
