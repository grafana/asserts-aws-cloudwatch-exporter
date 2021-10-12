/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;


import ai.asserts.aws.cloudwatch.model.CWNamespace;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapeConfig {
    private Set<String> regions;
    private List<NamespaceConfig> namespaces;

    @Builder.Default
    private Integer scrapeInterval = 60;

    @Builder.Default
    private Integer period = 300;

    public Optional<NamespaceConfig> getLambdaConfig() {
        if (CollectionUtils.isEmpty(namespaces)) {
            return Optional.empty();
        }
        return namespaces.stream()
                .filter(namespaceConfig -> namespaceConfig.getName().equals(CWNamespace.lambda.name()))
                .findFirst();
    }
}
