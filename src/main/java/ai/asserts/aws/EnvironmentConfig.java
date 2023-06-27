/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentConfig {
    private final boolean processingOff;

    public EnvironmentConfig(@Value("${aws.exporter.processing.off:false}") String exporterOff) {
        processingOff = "true".equalsIgnoreCase(exporterOff) || "yes".equalsIgnoreCase(exporterOff) || "y".equalsIgnoreCase(
                exporterOff);
    }

    public boolean isProcessingOff() {
        return processingOff;
    }

    public boolean isProcessingOn() {
        return !processingOff;
    }
}
