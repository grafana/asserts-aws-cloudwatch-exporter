
package ai.asserts.aws.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

@Getter
public enum CWNamespace {
    alb("AWS/ApplicationELB", "aws_alb", "alb", "elasticloadbalancing"),
    apigateway("AWS/ApiGateway", "aws_apigateway", "apigateway", null),
    appsync("AWS/AppSync", "aws_appsync", "appsync", null),
    billing("AWS/Billing", "aws_billing", "billing", null),
    cf("AWS/CloudFront", "aws_cloudfront", "cf", null),
    cloudwatch("AWS/CloudWatch", "aws_cloudwatch", "cloudwatch", null),
    docdb("AWS/DocDB", "aws_docdb", "docdb", null),
    dynamodb("AWS/DynamoDB", "aws_dynamodb", "dynamodb", "dynamodb", "table", "index"),
    ebs("AWS/EBS", "aws_ebs", "ebs", null),
    elasticache("AWS/ElastiCache", "aws_elasticache", "elasticache", null),
    ec2("AWS/EC2", "aws_ec2", "ec2", null),
    ec2Spot("AWS/EC2Spot", "aws_ec2spot", "ec2Spot", null),
    ecs_svc("AWS/ECS", "aws_ecs", "ecs", "ecs", "cluster", "service", "task-definition"),
    ecs_containerinsights("ECS/ContainerInsights", "aws_ecs_containerinsights", "ecs", "ecs", "cluster", "service",
            "task-definition"),
    efs("AWS/EFS", "aws_efs", "efs", null),
    elb("AWS/ELB", "aws_elb", "elb", "elasticloadbalancing","loadbalancer"),
    emr("AWS/ElasticMapReduce", "aws_emr", "elasticmapreduce", null),
    es("AWS/ES", "aws_es", "es", null),
    fsx("AWS/FSx", "aws_fsx", "fsx", null),
    gamelift("AWS/GameLift", "aws_gamelift", "gamelift", null),
    glue("Glue", "aws_glue", "glue", null),
    kinesis("AWS/Kinesis", "aws_kinesis", "kinesis", "kinesis"),
    kinesis_analytics("AWS/KinesisAnalytics", "aws_kinesis_analytics", "kinesis", "kinesisanalytics"),
    nfw("AWS/NetworkFirewall", "aws_nfw", "nfw", null),
    ngw("AWS/NATGateway", "aws_ngw", "ngw", null),
    lambda("AWS/Lambda", "aws_lambda", "lambda", "lambda","function"),
    lambdainsights("LambdaInsights", "aws_lambda", "lambda",  "lambda","function"),
    nlb("AWS/NetworkELB", "aws_nlb", "nlb", null),
    redshift("AWS/Redshift", "aws_redshift", "redshift", null),
    rds("AWS/RDS", "aws_rds", "rds", null),
    r53r("AWS/Route53Resolver", "aws_r53r", "r53r", null),
    s3("AWS/S3", "aws_s3", "s3", null),
    ses("AWS/SES", "aws_ses", "ses", null),
    sqs("AWS/SQS", "aws_sqs", "sqs", null),
    tgw("AWS/TransitGateway", "aws_tgw", "tgw", null),
    vpn("AWS/VPN", "aws_vpn", "vpn", null),
    asg("AWS/AutoScaling", "aws_asg", "asg", null),
    kafka("AWS/Kafka", "aws_kafka", "kafka", null),
    firehose("AWS/Firehose", "aws_firehose", "firehose", "firehose"),
    sns("AWS/SNS", "aws_sns", "sns", null),
    sfn("AWS/States", "aws_sfn", "sfn", null),
    wafv2("AWS/WAFV2", "aws_wav2", "wafv2", null);

    private final String namespace;
    /**
     * In some cases the same AWS Resource like a Lambda Function or ECS Service has metrics under different
     * namespaces. We unify these under a single namespace in such cases using the normalized namespace
     */
    private final String normalizedNamespace;
    private final String metricPrefix;
    private final String serviceName;
    private final String serviceNameForTagApi;
    private final Set<String> resourceTypes;

    CWNamespace(String namespace, String prefix, String serviceName,
                String serviceNameForTagApi, String... resourceTypes) {
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.serviceNameForTagApi = serviceNameForTagApi;
        this.metricPrefix = prefix;
        this.resourceTypes = new TreeSet<>(Arrays.asList(resourceTypes));
        switch (namespace) {
            case "AWS/ECS":
            case "ECS/ContainerInsights":
                normalizedNamespace = "AWS/ECS";
                break;
            case "AWS/Lambda":
            case "LambdaInsights":
                normalizedNamespace = "AWS/Lambda";
                break;
            default:
                normalizedNamespace = namespace;
        }
    }

    public String getServiceNameForTagApi() {
        return Stream.of(serviceNameForTagApi, serviceName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public boolean isThisNamespace(String name) {
        return name().equals(name) || namespace.equals(name);
    }
}
