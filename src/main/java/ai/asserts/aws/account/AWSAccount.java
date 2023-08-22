/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Set;
import java.util.TreeSet;

@AllArgsConstructor
@Getter
@EqualsAndHashCode
@ToString
@SuperBuilder
@NoArgsConstructor
public class AWSAccount {
    @Setter
    private String tenant;
    // Use different json field name to match property from API Response
    @JsonProperty("accountID")
    private String accountId;
    private String name;
    @ToString.Exclude
    private String accessId;
    @ToString.Exclude
    private String secretKey;
    // Use different json field name to match property from API Response
    @JsonProperty("assumeRoleARN")
    private String assumeRole;
    private String externalId;
    private boolean paused;
    @Builder.Default
    private final Set<String> regions = new TreeSet<>();

    public AWSAccount(String tenant, String accountId, String accessId, String secretKey, String assumeRole,
                      Set<String> regions) {
        this(tenant, accountId, null, accessId, secretKey, assumeRole, null, false, regions);
    }
}
