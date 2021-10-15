/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

@Getter
public enum CWNamespace {
    alb("AWS/ApplicationELB", "alb"),
    apigateway("AWS/ApiGateway", "apigateway"),
    appsync("AWS/AppSync", "appsync"),
    billing("AWS/Billing", "billing"),
    cf("AWS/CloudFront", "cf"),
    docdb("AWS/DocDB", "docdb"),
    dynamodb("AWS/DynamoDB", "dynamodb", "table", "index"),
    ebs("AWS/EBS", "ebs"),
    ec("AWS/Elasticache", "ec"),
    ec2("AWS/EC2", "ec2"),
    ec2Spot("AWS/EC2Spot", "ec2Spot"),
    ecs_svc("AWS/ECS", "ecs-svc"),
    ecs_containerinsights("ECS/ContainerInsights", "ecs-containerinsights"),
    efs("AWS/EFS", "efs"),
    elb("AWS/ELB", "elb"),
    emr("AWS/ElasticMapReduce", "emr"),
    es("AWS/ES", "es"),
    fsx("AWS/FSx", "fsx"),
    gamelift("AWS/GameLift", "gamelift"),
    glue("Glue", "glue"),
    kinesis("AWS/Kinesis", "kinesis"),
    nfw("AWS/NetworkFirewall", "nfw"),
    ngw("AWS/NATGateway", "ngw"),
    lambda("AWS/Lambda", "lambda", "function"),
    lambdainsights("LambdaInsights","lambda","function"),
    nlb("AWS/NetworkELB", "nlb"),
    redshift("AWS/Redshift", "redshift"),
    rds("AWS/RDS", "rds"),
    r53r("AWS/Route53Resolver", "r53r"),
    s3("AWS/S3", "s3"),
    ses("AWS/SES", "ses"),
    sqs("AWS/SQS", "sqs"),
    tgw("AWS/TransitGateway", "tgw"),
    vpn("AWS/VPN", "vpn"),
    asg("AWS/AutoScaling", "asg"),
    kafka("AWS/Kafka", "kafka"),
    firehose("AWS/Firehose", "firehose"),
    sns("AWS/SNS", "sns"),
    sfn("AWS/States", "sfn"),
    wafv2("AWS/WAFV2", "wafv2");

    private final String namespace;
    private final String serviceName;
    private final Set<String> resourceTypes;

    CWNamespace(String namespace, String serviceName, String... resourceTypes) {
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.resourceTypes = new TreeSet<>(Arrays.asList(resourceTypes));
    }
}
