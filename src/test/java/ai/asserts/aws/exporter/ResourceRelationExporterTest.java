/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.SortedMap;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceRelationExporterTest extends EasyMockSupport {
    private LBToASGRelationBuilder lbToASGRelationBuilder;
    private LBToLambdaRoutingBuilder lbToLambdaRoutingBuilder;
    private EC2ToEBSVolumeExporter ec2ToEBSVolumeExporter;
    private ApiGatewayToLambdaBuilder apiGatewayToLambdaBuilder;
    private LBToECSRoutingBuilder lbToECSRoutingBuilder;
    private MetricSampleBuilder sampleBuilder;
    private Resource fromResource;
    private Resource toResource;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private ResourceRelationExporter testClass;

    @BeforeEach
    public void setup() {
        lbToASGRelationBuilder = mock(LBToASGRelationBuilder.class);
        lbToLambdaRoutingBuilder = mock(LBToLambdaRoutingBuilder.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        fromResource = mock(Resource.class);
        toResource = mock(Resource.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        ec2ToEBSVolumeExporter = mock(EC2ToEBSVolumeExporter.class);
        apiGatewayToLambdaBuilder = mock(ApiGatewayToLambdaBuilder.class);
        lbToECSRoutingBuilder = mock(LBToECSRoutingBuilder.class);
        testClass = new ResourceRelationExporter(
                lbToASGRelationBuilder, lbToLambdaRoutingBuilder, lbToECSRoutingBuilder, ec2ToEBSVolumeExporter,
                apiGatewayToLambdaBuilder,
                sampleBuilder);
    }

    @Test
    public void update() {
        expect(fromResource.getRegion()).andReturn("us-west-2").anyTimes();
        expect(lbToECSRoutingBuilder.getRouting()).andReturn(ImmutableSet.of(ResourceRelation.builder()
                .from(fromResource)
                .to(toResource)
                .name("name1")
                .build()));

        fromResource.addLabels(anyObject(SortedMap.class), eq("from"));
        expectLastCall().times(5);
        toResource.addLabels(anyObject(SortedMap.class), eq("to"));
        expectLastCall().times(5);

        expect(lbToASGRelationBuilder.getRoutingConfigs()).andReturn(ImmutableSet.of(ResourceRelation.builder()
                .from(fromResource)
                .to(toResource)
                .name("name2")
                .build()));
        expect(lbToLambdaRoutingBuilder.getRoutings()).andReturn(ImmutableSet.of(ResourceRelation.builder()
                .from(fromResource)
                .to(toResource)
                .name("name3")
                .build()));

        expect(ec2ToEBSVolumeExporter.getAttachedVolumes()).andReturn(ImmutableSet.of(
                ResourceRelation.builder().from(fromResource).to(toResource).name("name4").build()
        ));

        expect(apiGatewayToLambdaBuilder.getLambdaIntegrations()).andReturn(ImmutableSet.of(
                ResourceRelation.builder().from(fromResource).to(toResource).name("name5").build()
        ));

        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of(
                        "asserts_site", "us-west-2",
                        "rel_name", "name1"), 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of(
                        "asserts_site", "us-west-2",
                        "rel_name", "name2"), 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of(
                        "asserts_site", "us-west-2",
                        "rel_name", "name3"), 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of(
                        "asserts_site", "us-west-2",
                        "rel_name", "name4"), 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of(
                        "asserts_site", "us-west-2",
                        "rel_name", "name5"), 1.0D))
                .andReturn(Optional.of(sample));
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample, sample, sample, sample)))
                .andReturn(Optional.of(familySamples));

        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }
}
