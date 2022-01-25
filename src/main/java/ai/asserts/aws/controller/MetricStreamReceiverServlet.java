/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.controller;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

//@WebServlet(
//        name = "AnnotationExample",
//        description = "Example Servlet Using Annotations",
//        urlPatterns = {"/receive-cloudwatch-metrics"}
//)
@Component
@AllArgsConstructor
@Slf4j
public class MetricStreamReceiverServlet extends HttpServlet {
    private final MetricStreamController metricStreamController;

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ServletInputStream inputStream = req.getInputStream();
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseDelimitedFrom(inputStream);
        metricStreamController.receiveMetrics(request);
        resp.setStatus(204);
    }
}
