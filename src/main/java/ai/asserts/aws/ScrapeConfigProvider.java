/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.model.CWNamespace;

import java.util.Optional;

public interface ScrapeConfigProvider {
    ScrapeConfig getScrapeConfig(String tenant);

    Optional<CWNamespace> getStandardNamespace(String namespace);

    void update();
}
