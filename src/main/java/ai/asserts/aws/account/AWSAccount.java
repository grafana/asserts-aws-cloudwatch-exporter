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
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Set;
import java.util.TreeSet;

@AllArgsConstructor
@Getter
@EqualsAndHashCode
@ToString
@SuperBuilder
public class AWSAccount {
    // Use different json field name to match property from API Response
    @JsonProperty("accountID")
    private final String accountId;
    private final String name;
    @ToString.Exclude
    private final String accessId;
    @ToString.Exclude
    private final String secretKey;
    // Use different json field name to match property from API Response
    @JsonProperty("assumeRoleARN")
    private final String assumeRole;
    private final String externalId;
    private final boolean paused;
    @Builder.Default
    private final Set<String> regions = new TreeSet<>();

    public AWSAccount(String accountId, String accessId, String secretKey, String assumeRole, Set<String> regions) {
        this(accountId, null, accessId, secretKey, assumeRole, null, false, regions);
    }
}
