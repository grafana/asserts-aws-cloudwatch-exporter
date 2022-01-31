/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch;

import com.google.common.collect.ImmutableList;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricStreamServletTest extends EasyMockSupport {

    @Test
    public void doPut() throws Exception {
        MetricStreamProcessor streamProcessor = mock(MetricStreamProcessor.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        ServletInputStream mockInputStream = mock(ServletInputStream.class);
        ExportMetricsServiceRequest mockPayload = ExportMetricsServiceRequest.newBuilder().build();

        expect(servletRequest.getInputStream()).andReturn(mockInputStream);
        streamProcessor.process(mockPayload);
        servletResponse.setStatus(204);

        replayAll();
        MetricStreamServlet testClass = new MetricStreamServlet(streamProcessor) {
            @Override
            ExportMetricsServiceRequest parsePayload(ServletInputStream inputStream) {
                assertEquals(mockInputStream, inputStream);
                return mockPayload;
            }
        };

        testClass.doPut(servletRequest, servletResponse);

        verifyAll();
    }

    @Test
    public void doPost() throws Exception {
        MetricStreamProcessor streamProcessor = mock(MetricStreamProcessor.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);

        List<Object> sideEffect = new ArrayList<>();

        replayAll();
        MetricStreamServlet testClass = new MetricStreamServlet(streamProcessor) {
            @Override
            protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                assertEquals(servletRequest, req);
                assertEquals(servletResponse, resp);
                sideEffect.add(1);
            }
        };

        testClass.doPut(servletRequest, servletResponse);
        assertEquals(ImmutableList.of(1), sideEffect);

        verifyAll();
    }
}
