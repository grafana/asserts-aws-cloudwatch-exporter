/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Getter
public class TaskThreadPool {
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
}

