/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.exporter.ECSServiceDiscoveryExporter.StaticConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ECSServiceDiscoveryControllerTest extends EasyMockSupport {

    @Test
    public void getECSSDConfig() throws Exception {
        ECSServiceDiscoveryExporter mockExporter = mock(ECSServiceDiscoveryExporter.class);
        ObjectMapperFactory oMF = mock(ObjectMapperFactory.class);
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        StaticConfig mockConfig = mock(StaticConfig.class);

        expect(mockExporter.getTargets()).andReturn(ImmutableList.of(mockConfig));
        expect(oMF.getObjectMapper()).andReturn(mockMapper);
        expect(mockMapper.writeValueAsString(ImmutableList.of(mockConfig))).andReturn("yaml-string");

        replayAll();
        ECSServiceDiscoveryController testClass = new ECSServiceDiscoveryController(mockExporter, oMF);

        assertEquals(ResponseEntity.ok("yaml-string"), testClass.getECSSDConfig());

        verifyAll();
    }
}
