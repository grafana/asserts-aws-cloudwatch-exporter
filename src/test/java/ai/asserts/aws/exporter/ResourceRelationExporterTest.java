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

import java.util.SortedMap;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceRelationExporterTest extends EasyMockSupport {
    private ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private LBToASGRelationBuilder lbToASGRelationBuilder;
    private LBToLambdaRoutingBuilder lbToLambdaRoutingBuilder;
    private MetricSampleBuilder sampleBuilder;
    private Resource fromResource;
    private Resource toResource;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private ResourceRelationExporter testClass;

    @BeforeEach
    public void setup() {
        ecsServiceDiscoveryExporter = mock(ECSServiceDiscoveryExporter.class);
        lbToASGRelationBuilder = mock(LBToASGRelationBuilder.class);
        lbToLambdaRoutingBuilder = mock(LBToLambdaRoutingBuilder.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        fromResource = mock(Resource.class);
        toResource = mock(Resource.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        testClass = new ResourceRelationExporter(ecsServiceDiscoveryExporter,
                lbToASGRelationBuilder, lbToLambdaRoutingBuilder, sampleBuilder);
    }

    @Test
    public void update() {
        expect(ecsServiceDiscoveryExporter.getRouting()).andReturn(ImmutableSet.of(ResourceRelation.builder()
                .from(fromResource)
                .to(toResource)
                .name("name1")
                .build()));

        fromResource.addLabels(anyObject(SortedMap.class), eq("from"));
        expectLastCall().times(3);
        toResource.addLabels(anyObject(SortedMap.class), eq("to"));
        expectLastCall().times(3);

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

        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of("rel_name", "name1"), 1.0D))
                .andReturn(sample);
        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of("rel_name", "name2"), 1.0D))
                .andReturn(sample);
        expect(sampleBuilder.buildSingleSample(
                "aws_resource_relation", ImmutableSortedMap.of("rel_name", "name3"), 1.0D))
                .andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample, sample, sample))).andReturn(familySamples);

        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }
}
