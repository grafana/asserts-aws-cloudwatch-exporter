/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import java.util.ArrayList;
import java.util.List;

public abstract class CollectionBuilderTask<T> extends TenantTask<List<T>> {
    @Override
    public List<T> getReturnValueWhenError() {
        return new ArrayList<>();
    }
}
