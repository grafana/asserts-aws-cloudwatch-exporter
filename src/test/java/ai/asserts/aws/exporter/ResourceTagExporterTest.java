/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static io.prometheus.client.Collector.Type.GAUGE;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ResourceTagExporterTest extends EasyMockSupport {
    private TagFilterResourceProvider tagFilterResourceProvider;
    private TagFilterResourceProvider.Key key;
    private Resource resource;
    private MetricNameUtil metricNameUtil;
    private MetricSampleBuilder metricSampleBuilder;
    private LoadingCache loadingCache;
    private Sample sample;
    private ResourceTagExporter testClass;

    @BeforeEach
    public void setup() {
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);
        resource = mock(Resource.class);
        metricNameUtil = mock(MetricNameUtil.class);
        metricSampleBuilder = mock(MetricSampleBuilder.class);
        loadingCache = mock(LoadingCache.class);
        key = mock(TagFilterResourceProvider.Key.class);
        sample = mock(Sample.class);
        testClass = new ResourceTagExporter(tagFilterResourceProvider, metricNameUtil, metricSampleBuilder);
    }

    @Test
    void collect() {
        expect(tagFilterResourceProvider.getResourceCache()).andReturn(loadingCache);
        expect(loadingCache.asMap()).andReturn(new ConcurrentHashMap(ImmutableMap.of(key, ImmutableSet.of(resource))));
        expect(resource.getRegion()).andReturn("region1");
        ImmutableMap<String, String> labels = ImmutableMap.of("region", "region1");
        resource.addLabels(labels, "resource");
        resource.addTagLabels(labels, metricNameUtil);
        expect(metricSampleBuilder.buildSingleSample("aws_resource_tags", labels, 1.0D))
                .andReturn(sample);
        replayAll();
        assertEquals(
                ImmutableList.of(new Collector.MetricFamilySamples("aws_resource_tags", GAUGE, "",
                        ImmutableList.of(sample))),
                testClass.collect()
        );
        verifyAll();
    }
}
