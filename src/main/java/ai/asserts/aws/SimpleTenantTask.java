/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

public abstract class SimpleTenantTask<T> extends TenantTask<T> {
    @Override
    public T getReturnValueWhenError() {
        return null;
    }
}
