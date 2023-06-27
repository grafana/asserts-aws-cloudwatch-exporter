package ai.asserts.aws;

import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.exporter.AccountIDProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClientBuilder;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.AutoScalingClientBuilder;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.EcsClientBuilder;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClientBuilder;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2ClientBuilder;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.emr.EmrClientBuilder;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.FirehoseClientBuilder;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.KinesisClientBuilder;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2Client;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2ClientBuilder;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.LambdaClientBuilder;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.RdsClientBuilder;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.RedshiftClientBuilder;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
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

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.springframework.util.StringUtils.hasLength;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Component
@Slf4j
public class AWSClientProvider {
    private final AccountIDProvider accountIDProvider;
    private final Map<AccountRegion, AWSSessionConfig> credentialCache = new ConcurrentHashMap<>();
    private final Cache<ClientCacheKey, SdkClient> clientCache;

    public AWSClientProvider(AccountIDProvider accountIDProvider) {
        this.accountIDProvider = accountIDProvider;
        Map<String, String> env = System.getenv();
        this.clientCache = CacheBuilder.newBuilder()
                .expireAfterAccess(Long.parseLong(env.getOrDefault("AWS_SDK_CLIENT_CACHE_TTL", "30")), MINUTES)
                .removalListener(removalNotification -> {
                    try {
                        SdkClient sdkClient = (SdkClient) removalNotification.getValue();
                        log.info("Shutting down SDK Client {}", sdkClient.serviceName());
                        sdkClient.close();
                    } catch (Exception e) {
                        log.error("Failed to close client", e);
                    }
                })
                .build();
    }

    public SecretsManagerClient getSecretsManagerClient(String region) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(accountIDProvider.getAccountId())
                .clientType(SecretsManagerClient.class)
                .build();
        SecretsManagerClient client = (SecretsManagerClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            client = SecretsManagerClient.builder()
                    .region(Region.of(region))
                    .build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public SqsClient getSqsClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(SqsClient.class)
                .build();
        SqsClient client = (SqsClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            SqsClientBuilder clientBuilder = SqsClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public SnsClient getSnsClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(SnsClient.class)
                .build();
        SnsClient client = (SnsClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            SnsClientBuilder clientBuilder = SnsClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public AutoScalingClient getAutoScalingClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(AutoScalingClient.class)
                .build();
        AutoScalingClient client = (AutoScalingClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            AutoScalingClientBuilder clientBuilder = AutoScalingClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public ApiGatewayClient getApiGatewayClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(ApiGatewayClient.class)
                .build();
        ApiGatewayClient client = (ApiGatewayClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            ApiGatewayClientBuilder clientBuilder = ApiGatewayClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public ElasticLoadBalancingV2Client getELBV2Client(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(ElasticLoadBalancingV2Client.class)
                .build();
        ElasticLoadBalancingV2Client client = (ElasticLoadBalancingV2Client) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            ElasticLoadBalancingV2ClientBuilder clientBuilder =
                    ElasticLoadBalancingV2Client.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public ElasticLoadBalancingClient getELBClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(ElasticLoadBalancingClient.class)
                .build();
        ElasticLoadBalancingClient client = (ElasticLoadBalancingClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            ElasticLoadBalancingClientBuilder clientBuilder =
                    ElasticLoadBalancingClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public CloudWatchClient getCloudWatchClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(CloudWatchClient.class)
                .build();
        CloudWatchClient client = (CloudWatchClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            CloudWatchClientBuilder clientBuilder = cloudWatchClientBuilder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public LambdaClient getLambdaClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(LambdaClient.class)
                .build();
        LambdaClient client = (LambdaClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            LambdaClientBuilder clientBuilder = LambdaClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public ResourceGroupsTaggingApiClient getResourceTagClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(ResourceGroupsTaggingApiClient.class)
                .build();
        ResourceGroupsTaggingApiClient client =
                (ResourceGroupsTaggingApiClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            ResourceGroupsTaggingApiClientBuilder clientBuilder =
                    ResourceGroupsTaggingApiClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public EcsClient getECSClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(EcsClient.class)
                .build();
        EcsClient client = (EcsClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            EcsClientBuilder clientBuilder = EcsClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public Ec2Client getEc2Client(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(Ec2Client.class)
                .build();
        Ec2Client client = (Ec2Client) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            Ec2ClientBuilder clientBuilder = Ec2Client.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public KinesisAnalyticsV2Client getKAClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(KinesisAnalyticsV2Client.class)
                .build();
        KinesisAnalyticsV2Client client = (KinesisAnalyticsV2Client) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            KinesisAnalyticsV2ClientBuilder clientBuilder =
                    KinesisAnalyticsV2Client.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public FirehoseClient getFirehoseClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(FirehoseClient.class)
                .build();
        FirehoseClient client = (FirehoseClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            FirehoseClientBuilder clientBuilder = FirehoseClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public S3Client getS3Client(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(S3Client.class)
                .build();
        S3Client client = (S3Client) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            S3ClientBuilder clientBuilder = S3Client.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public DynamoDbClient getDynamoDBClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(DynamoDbClient.class)
                .build();
        DynamoDbClient client = (DynamoDbClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            DynamoDbClientBuilder clientBuilder = DynamoDbClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public RedshiftClient getRedshiftClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(RedshiftClient.class)
                .build();
        RedshiftClient client = (RedshiftClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            RedshiftClientBuilder clientBuilder = RedshiftClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public EmrClient getEmrClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(EmrClient.class)
                .build();
        EmrClient client = (EmrClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            EmrClientBuilder clientBuilder = EmrClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public KinesisClient getKinesisClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(KinesisClient.class)
                .build();
        KinesisClient client = (KinesisClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            KinesisClientBuilder clientBuilder = KinesisClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    public RdsClient getRDSClient(String region, AWSAccount account) {
        ClientCacheKey clientCacheKey = ClientCacheKey.builder()
                .region(region)
                .accountId(account.getAccountId())
                .clientType(RdsClient.class)
                .build();
        RdsClient client = (RdsClient) clientCache.getIfPresent(clientCacheKey);
        if (client == null) {
            RdsClientBuilder clientBuilder = RdsClient.builder().region(Region.of(region));
            Optional<AwsCredentialsProvider> credentialsOpt = getCredentialsProvider(account);
            if (account.getAssumeRole() != null) {
                clientBuilder = clientBuilder.credentialsProvider(() ->
                        getAwsSessionCredentials(region, account, credentialsOpt));
            } else if (credentialsOpt.isPresent()) {
                clientBuilder = clientBuilder.credentialsProvider(credentialsOpt.get());
            }
            client = clientBuilder.build();
            clientCache.put(clientCacheKey, client);
        }
        return client;
    }

    @VisibleForTesting
    CloudWatchClientBuilder cloudWatchClientBuilder() {
        return CloudWatchClient.builder();
    }

    @VisibleForTesting
    StsClientBuilder stsBuilder() {
        return StsClient.builder();
    }

    private AwsSessionCredentials getAwsSessionCredentials(String region, AWSAccount account,
                                                           Optional<AwsCredentialsProvider> credentialsOpt) {
        AccountRegion key = new AccountRegion(account, region);
        AWSSessionConfig credentials = credentialCache.get(key);
        if (credentials == null || credentials.getExpiring().compareTo(Instant.now()) <= 0) {
            StsClientBuilder stsClientBuilder = stsBuilder().region(Region.of(region));
            if (credentialsOpt.isPresent()) {
                stsClientBuilder = stsClientBuilder.credentialsProvider(credentialsOpt.get());
            }
            try (StsClient build = stsClientBuilder.build()) {
                AssumeRoleRequest.Builder reqBuilder = AssumeRoleRequest.builder()
                        .roleSessionName("session1")
                        .roleArn(account.getAssumeRole());
                if (hasLength(account.getExternalId())) {
                    reqBuilder = reqBuilder.externalId(account.getExternalId());
                }
                AssumeRoleResponse response = build.assumeRole(reqBuilder.build());
                credentials = AWSSessionConfig.builder()
                        .accessKeyId(response.credentials().accessKeyId())
                        .secretAccessKey(response.credentials().secretAccessKey())
                        .sessionToken(response.credentials().sessionToken())
                        .expiring(response.credentials().expiration())
                        .build();
                credentialCache.put(key, credentials);
            }
        }
        return AwsSessionCredentials.create(
                credentials.getAccessKeyId(),
                credentials.getSecretAccessKey(),
                credentials.getSessionToken());
    }

    private Optional<AwsCredentialsProvider> getCredentialsProvider(AWSAccount authConfig) {
        if (hasLength(authConfig.getAccessId()) && hasLength(authConfig.getSecretKey())) {
            return Optional.of(() -> AwsBasicCredentials.create(authConfig.getAccessId(), authConfig.getSecretKey()));
        }
        return Optional.empty();
    }

    @EqualsAndHashCode
    @Getter
    @AllArgsConstructor
    @Builder
    public static class ClientCacheKey {
        private final String accountId;
        private final String region;
        private final Class<?> clientType;
    }

    @EqualsAndHashCode
    @Getter
    @AllArgsConstructor
    public static class AccountRegion {
        private final AWSAccount account;
        private final String region;
    }
}
