/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ECSServiceDiscoveryControllerTest extends EasyMockSupport {

    @Test
    public void getECSSDConfig() {
        ECSTaskProvider mockExporter = mock(ECSTaskProvider.class);
        List mockConfig = mock(List.class);

        expect(mockExporter.getScrapeTargets()).andReturn(mockConfig);

        replayAll();
        ECSServiceDiscoveryController testClass = new ECSServiceDiscoveryController(mockExporter);

        assertEquals(ResponseEntity.ok((mockConfig)), testClass.getECSSDConfig());

        verifyAll();
    }
}
