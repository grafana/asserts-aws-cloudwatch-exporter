/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch;

import com.google.common.annotations.VisibleForTesting;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * See https://docs.aws.amazon.com/firehose/latest/dev/httpdeliveryrequestresponse.html
 * for the endpoint specification. The HTTP Method invoked is not specified. We also assume
 * that the payload will be delivered in OpenTelemetry 0.7 format
 */
@Component
@AllArgsConstructor
@Slf4j
public class MetricStreamServlet extends HttpServlet {
    private final MetricStreamProcessor metricStreamProcessor;

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doPutDoPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doPutDoPost(req, resp);
    }

    private void doPutDoPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ServletInputStream inputStream = req.getInputStream();
        ExportMetricsServiceRequest request = parsePayload(inputStream);
        metricStreamProcessor.process(request);
        resp.setStatus(204);
    }

    @VisibleForTesting
    ExportMetricsServiceRequest parsePayload(ServletInputStream inputStream) throws IOException {
        return ExportMetricsServiceRequest.parseDelimitedFrom(inputStream);
    }
}
