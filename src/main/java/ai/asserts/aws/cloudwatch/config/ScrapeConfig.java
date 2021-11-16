
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
@SuppressWarnings("FieldMayBeFinal")
public class ScrapeConfig {
    private Set<String> regions;
    private List<NamespaceConfig> namespaces;

    @Builder.Default
    private Integer scrapeInterval = 60;

    @Builder.Default
    private Integer period = 300;

    @Builder.Default
    private Integer delay = 0;

    @Builder.Default
    private Integer listMetricsResultCacheTTLMinutes = 10;

    @Builder.Default
    private Integer listFunctionsResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer getResourcesResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer numTaskThreads = 10;

    public Optional<NamespaceConfig> getLambdaConfig() {
        if (CollectionUtils.isEmpty(namespaces)) {
            return Optional.empty();
        }
        return namespaces.stream()
                .filter(namespaceConfig -> namespaceConfig.getName().equals(CWNamespace.lambda.name()))
                .findFirst();
    }
}
