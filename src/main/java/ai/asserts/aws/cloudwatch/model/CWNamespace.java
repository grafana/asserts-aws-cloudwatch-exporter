
package ai.asserts.aws.cloudwatch.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

@Getter
public enum CWNamespace {
    alb("AWS/ApplicationELB", "aws_alb", "alb"),
    apigateway("AWS/ApiGateway", "aws_apigateway", "apigateway"),
    appsync("AWS/AppSync", "aws_appsync", "appsync"),
    billing("AWS/Billing", "aws_billing", "billing"),
    cf("AWS/CloudFront", "aws_cloudfront", "cf"),
    docdb("AWS/DocDB", "aws_docdb", "docdb"),
    dynamodb("AWS/DynamoDB", "aws_dynamodb", "dynamodb", "table", "index"),
    ebs("AWS/EBS", "aws_ebs", "ebs"),
    ec("AWS/Elasticache", "aws_elasticache", "ec"),
    ec2("AWS/EC2", "aws_ec2", "ec2"),
    ec2Spot("AWS/EC2Spot", "aws_ec2spot", "ec2Spot"),
    ecs_svc("AWS/ECS", "aws_ecs", "ecs", "cluster", "service", "task-definition"),
    ecs_containerinsights("ECS/ContainerInsights", "aws_ecs_containerinsights", "ecs", "cluster", "service", "task-definition"),
    efs("AWS/EFS", "aws_efs", "efs"),
    elb("AWS/ELB", "aws_elb", "elb"),
    emr("AWS/ElasticMapReduce", "aws_emr", "emr"),
    es("AWS/ES", "aws_es", "es"),
    fsx("AWS/FSx", "aws_fsx", "fsx"),
    gamelift("AWS/GameLift", "aws_gamelift", "gamelift"),
    glue("Glue", "aws_glue", "glue"),
    kinesis("AWS/Kinesis", "aws_kinesis", "kinesis"),
    nfw("AWS/NetworkFirewall", "aws_nfw", "nfw"),
    ngw("AWS/NATGateway", "aws_ngw", "ngw"),
    lambda("AWS/Lambda", "aws_lambda", "lambda", "function"),
    lambdainsights("LambdaInsights", "aws_lambda", "lambda", "function"),
    nlb("AWS/NetworkELB", "aws_nlb", "nlb"),
    redshift("AWS/Redshift", "aws_redshift", "redshift"),
    rds("AWS/RDS", "aws_rds", "rds"),
    r53r("AWS/Route53Resolver", "aws_r53r", "r53r"),
    s3("AWS/S3", "aws_s3", "s3"),
    ses("AWS/SES", "aws_ses", "ses"),
    sqs("AWS/SQS", "aws_sqs", "sqs"),
    tgw("AWS/TransitGateway", "aws_tgw", "tgw"),
    vpn("AWS/VPN", "aws_vpn", "vpn"),
    asg("AWS/AutoScaling", "aws_asg", "asg"),
    kafka("AWS/Kafka", "aws_kafka", "kafka"),
    firehose("AWS/Firehose", "aws_firehose", "firehose"),
    sns("AWS/SNS", "aws_sns", "sns"),
    sfn("AWS/States", "aws_sfn", "sfn"),
    wafv2("AWS/WAFV2", "aws_wav2", "wafv2");

    private final String namespace;
    private final String metricPrefix;
    private final String serviceName;
    private final Set<String> resourceTypes;

    CWNamespace(String namespace, String prefix, String serviceName, String... resourceTypes) {
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.metricPrefix = prefix;
        this.resourceTypes = new TreeSet<>(Arrays.asList(resourceTypes));
    }
}
