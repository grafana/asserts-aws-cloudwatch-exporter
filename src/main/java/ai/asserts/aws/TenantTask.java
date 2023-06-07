/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.Getter;

import java.util.concurrent.Callable;

public abstract class TenantTask<T> implements Callable<T> {
    @Getter
    protected T result;

    public abstract T getReturnValueWhenError();
}
