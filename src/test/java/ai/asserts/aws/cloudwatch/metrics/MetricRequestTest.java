/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.metrics;

import ai.asserts.aws.ObjectMapperFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MetricRequestTest {
    @Test
    void sampleJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapperFactory().getObjectMapper();
        File file = new File("src/test/resources/cw-metric-stream-test.json");
        MetricsRequest metricsRequest = objectMapper.readValue(file, new TypeReference<MetricsRequest>() {
        });
        Base64.Decoder decoder = Base64.getDecoder();
        metricsRequest.getRecords().forEach(metricRecord -> {
            System.out.println(new String(decoder.decode(metricRecord.getData()), UTF_8));
        });
    }
}
