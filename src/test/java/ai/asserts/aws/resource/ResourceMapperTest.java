
package ai.asserts.aws.resource;

import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.asserts.aws.resource.ResourceType.APIGatewayMethod;
import static ai.asserts.aws.resource.ResourceType.APIGatewayResource;
import static ai.asserts.aws.resource.ResourceType.APIGatewayStage;
import static ai.asserts.aws.resource.ResourceType.Alarm;
import static ai.asserts.aws.resource.ResourceType.ApiGateway;
import static ai.asserts.aws.resource.ResourceType.AutoScalingGroup;
import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;
import static ai.asserts.aws.resource.ResourceType.ECSCluster;
import static ai.asserts.aws.resource.ResourceType.ECSService;
import static ai.asserts.aws.resource.ResourceType.ECSTask;
import static ai.asserts.aws.resource.ResourceType.ECSTaskDef;
import static ai.asserts.aws.resource.ResourceType.EventBus;
import static ai.asserts.aws.resource.ResourceType.Kinesis;
import static ai.asserts.aws.resource.ResourceType.KinesisAnalytics;
import static ai.asserts.aws.resource.ResourceType.KinesisDataFirehose;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.LoadBalancer;
import static ai.asserts.aws.resource.ResourceType.Redshift;
import static ai.asserts.aws.resource.ResourceType.S3Bucket;
import static ai.asserts.aws.resource.ResourceType.SNSTopic;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;
import static ai.asserts.aws.resource.ResourceType.TargetGroup;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceMapperTest extends EasyMockSupport {
    private ResourceMapper testClass;

    @BeforeEach
    public void setup() {
        TaskExecutorUtil taskExecutorUtil = mock(TaskExecutorUtil.class);
        testClass = new ResourceMapper(taskExecutorUtil);
        expect(taskExecutorUtil.getAccountDetails()).andReturn(AWSAccount.builder()
                .tenant("acme")
                .build()).anyTimes();
    }

    @Test
    public void map_SQSQueue() {
        replayAll();
        String arn = "arn:aws:sqs:us-west-2:342994379019:lamda-sqs-poc-input-queue";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(SQSQueue).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("lamda-sqs-poc-input-queue")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_SQSQueue_URL() {
        replayAll();
        String url = "https://sqs.us-west-2.amazonaws.com/342994379019/ErrorDemo-Input";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(SQSQueue).arn("arn:aws:sqs:us-west-2:342994379019:ErrorDemo-Input")
                        .region("us-west-2")
                        .account("342994379019")
                        .name("ErrorDemo-Input")
                        .build()),
                testClass.map(url)
        );
        verifyAll();
    }

    @Test
    public void map_DynamoDBTable() {
        replayAll();
        String arn = "arn:aws:dynamodb:us-west-2:342994379019:table/auction_app_bids/stream/2021-06-01T05:03:12.707";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(DynamoDBTable)
                        .arn(arn)
                        .account("342994379019")
                        .region("us-west-2")
                        .name("auction_app_bids")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_LambdaFunction() {
        replayAll();
        String arn = "arn:aws:lambda:us-west-2:342994379019:function:lambda-poc-dynamodb-updates";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(LambdaFunction)
                        .region("us-west-2")
                        .account("342994379019")
                        .arn(arn).name("lambda-poc-dynamodb-updates")
                        .build()),
                testClass.map(arn)
        );

        arn = "arn:aws:lambda:us-west-2:342994379019:function:lambda-poc-dynamodb-updates:version1";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(LambdaFunction)
                        .region("us-west-2")
                        .account("342994379019")
                        .arn(arn).name("lambda-poc-dynamodb-updates")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_S3Bucket() {
        replayAll();
        String arn = "arn:aws:s3:::ai-asserts-dev-custom-rules";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(S3Bucket).arn(arn)
                        .region("")
                        .account("")
                        .name("ai-asserts-dev-custom-rules")
                        .build()),
                testClass.map(arn)
        );

        arn = "arn:aws:s3:us-west-2:342994379019:ai-asserts-dev-custom-rules";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(S3Bucket)
                        .region("us-west-2")
                        .account("342994379019")
                        .arn(arn).name("ai-asserts-dev-custom-rules")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_SNS_Topic() {
        replayAll();
        String arn = "arn:aws:sns:us-west-2:342994379019:topic-name";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(SNSTopic).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("topic-name")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_EventBus_Topic() {
        replayAll();
        String arn = "arn:aws:events:us-west-2:342994379019:event-bus/event-bus-name";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(EventBus).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("event-bus-name")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_ECS_Cluster() {
        replayAll();
        String arn = "arn:aws:ecs:us-west-2:342994379019:cluster/cluster1";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(ECSCluster).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("cluster1")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_ECS_Service() {
        replayAll();
        String arn = "arn:aws:ecs:us-west-2:342994379019:service/ecs-cluster/service1";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(ECSService).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("service1")
                        .childOf(Resource.builder()
                                .account("342994379019")
                                .type(ECSCluster)
                                .name("ecs-cluster")
                                .region("us-west-2").build())
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_ECS_TaskDefinition() {
        replayAll();
        String arn = "arn:aws:ecs:us-west-2:342994379019:task-definition/item-service-v2:5";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(ECSTaskDef).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("item-service-v2")
                        .version("5")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_ECS_Task() {
        replayAll();
        String arn = "arn:aws:ecs:us-west-2:342994379019:task/ecs-sample-app/34c11488dc56429fb67e2996b5ceaa74";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(ECSTask).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("34c11488dc56429fb67e2996b5ceaa74")
                        .childOf(Resource.builder()
                                .type(ECSCluster)
                                .account("342994379019")
                                .region("us-west-2")
                                .name("ecs-sample-app")
                                .build())
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_LoadBalancer() {
        replayAll();
        String arn =
                "arn:aws:elasticloadbalancing:us-west-2:342994379019:loadbalancer/app/k8s-assertsinternal-dabf78ac56" +
                        "/ffc311c1118b747a";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(LoadBalancer).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .subType("app")
                        .name("k8s-assertsinternal-dabf78ac56")
                        .id("ffc311c1118b747a")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_ClassicLoadBalancer() {
        replayAll();
        String arn = "arn:aws:elasticloadbalancing:us-west-2:342994379019:loadbalancer/k8s-assertsinternal-dabf78ac56";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(LoadBalancer).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("k8s-assertsinternal-dabf78ac56")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_TargetGroup() {
        replayAll();
        String arn =
                "arn:aws:elasticloadbalancing:us-west-2:342994379019:targetgroup/auction-bid-service-tg" +
                        "/f2f15d26b40e68f2";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(TargetGroup).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("auction-bid-service-tg")
                        .id("f2f15d26b40e68f2")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_AutoScalingGroup() {
        replayAll();
        String arn =
                "arn:aws:autoscaling:us-west-2:342994379019:autoScalingGroup:ffc311c1118b747a:autoScalingGroupName" +
                        "/groupName";
        assertEquals(
                Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(AutoScalingGroup).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .id("ffc311c1118b747a")
                        .name("groupName")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_APIGateway() {
        replayAll();
        String arn = "arn:aws:apigateway:us-west-2::/restapis/nvaaoiotuc";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(ApiGateway).arn(arn)
                        .region("us-west-2")
                        .account("")
                        .subType("restapis")
                        .name("nvaaoiotuc")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_APIGateway_Stage() {
        replayAll();
        String arn = "arn:aws:apigateway:us-west-2::/restapis/nvaaoiotuc/stages/dev";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(APIGatewayStage).arn(arn)
                        .region("us-west-2")
                        .account("")
                        .name("dev")
                        .childOf(Resource.builder()
                                .region("us-west-2")
                                .type(ApiGateway)
                                .account("")
                                .subType("restapis")
                                .name("nvaaoiotuc")
                                .build())
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_APIGateway_Resource() {
        replayAll();
        String arn = "arn:aws:apigateway:us-west-2::/restapis/nvaaoiotuc/resources/dev";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(APIGatewayResource).arn(arn)
                        .region("us-west-2")
                        .account("")
                        .name("dev")
                        .childOf(Resource.builder()
                                .region("us-west-2")
                                .type(ApiGateway)
                                .account("")
                                .subType("restapis")
                                .name("nvaaoiotuc")
                                .build())
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_APIGateway_Method() {
        replayAll();
        String arn = "arn:aws:apigateway:us-west-2::/restapis/nvaaoiotuc/resources/resourrce1/methods/dev";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(APIGatewayMethod).arn(arn)
                        .region("us-west-2")
                        .account("")
                        .name("dev")
                        .childOf(Resource.builder()
                                .region("us-west-2")
                                .type(ApiGateway)
                                .account("")
                                .subType("restapis")
                                .name("nvaaoiotuc")
                                .build())
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_Alarm() {
        replayAll();
        String arn =
                "arn:aws:cloudwatch:us-west-2:342994379019:alarm:TargetTracking-table/GameScores/index/GameTitle" +
                        "-TopScore-index-ProvisionedCapacityLow-fc66d6b6-6a14-4303-9dd5-70a4714d8cd0";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(Alarm).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("TargetTracking-table/GameScores/index/GameTitle-TopScore-index-ProvisionedCapacityLow" +
                                "-fc66d6b6-6a14-4303-9dd5-70a4714d8cd0")
                        .build()),
                testClass.map(arn)
        );
        verifyAll();
    }

    @Test
    public void map_Kinesis() {
        replayAll();
        String arn = "arn:aws:kinesis:us-west-2:342994379019:stream/Asserts-CloudWatch-DataStream";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(Kinesis).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("Asserts-CloudWatch-DataStream")
                        .build()),
                testClass.map(arn));
        verifyAll();
    }

    @Test
    public void map_KinesisAnalytics() {
        replayAll();
        String arn = "arn:aws:kinesisanalytics:us-west-2:342994379019:application/Asserts-CloudWatch-DataStream";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(KinesisAnalytics).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("Asserts-CloudWatch-DataStream")
                        .build()),
                testClass.map(arn));
        verifyAll();
    }

    @Test
    public void map_KinesisDataFirehose() {
        replayAll();
        String arn = "arn:aws:firehose:us-west-2:342994379019:deliverystream/Asserts-CloudWatch-DataStream";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(KinesisDataFirehose).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("Asserts-CloudWatch-DataStream")
                        .build()),
                testClass.map(arn));
        verifyAll();
    }

    @Test
    public void map_Redshift() {
        replayAll();
        String arn = "arn:aws:redshift:us-west-2:342994379019:cluster/Asserts-redshift-cluster1";
        assertEquals(Optional.of(Resource.builder()
                        .tenant("acme")
                        .type(Redshift).arn(arn)
                        .region("us-west-2")
                        .account("342994379019")
                        .name("Asserts-redshift-cluster1")
                        .build()),
                testClass.map(arn));
        verifyAll();
    }
}
