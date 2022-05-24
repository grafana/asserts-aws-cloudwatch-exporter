/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.ListStreamsResponse;

import java.util.SortedMap;
import java.util.TreeMap;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KinesisStreamExporterTest extends EasyMockSupport {

    public CollectorRegistry collectorRegistry;
    private AccountProvider.AWSAccount accountRegion;
    private AWSClientProvider awsClientProvider;
    private RateLimiter rateLimiter;
    private MetricSampleBuilder sampleBuilder;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private KinesisClient kinesisClient;
    private KinesisStreamExporter testClass;

    @BeforeEach
    public void setup() {
        accountRegion = new AccountProvider.AWSAccount("account1", "", "",
                "role", ImmutableSet.of("region1"));
        AccountProvider accountProvider = mock(AccountProvider.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(RateLimiter.class);
        collectorRegistry = mock(CollectorRegistry.class);
        kinesisClient = mock(KinesisClient.class);
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion));
        testClass = new KinesisStreamExporter(accountProvider, awsClientProvider, collectorRegistry, rateLimiter, sampleBuilder);
    }

    @Test
    public void exporterStreamTest() {

        SortedMap<String, String> labels1 = new TreeMap<>();
        labels1.put("region", "region1");
        labels1.put("name", "stream1");
        labels1.put("id", "stream1");
        labels1.put("job", "stream1");
        labels1.put("namespace", "AWS/Kinesis");
        labels1.put(SCRAPE_ACCOUNT_ID_LABEL, "account1");
        labels1.put("aws_resource_type", "AWS::Kinesis::Stream");
        ListStreamsResponse response = ListStreamsResponse
                .builder()
                .streamNames("stream1")
                .build();
        Capture<RateLimiter.AWSAPICall<ListStreamsResponse>> callbackCapture = Capture.newInstance();

        expect(rateLimiter.doWithRateLimit(eq("KinesisClient/listStreams"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        expect(awsClientProvider.getKinesisClient("region1", accountRegion)).andReturn(kinesisClient);
        expect(sampleBuilder.buildSingleSample("aws_resource", labels1, 1.0D))
                .andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(familySamples);
        kinesisClient.close();
        expectLastCall();
        replayAll();
        testClass.update();
        testClass.collect();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());
        verifyAll();
    }
}
