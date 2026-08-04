package com.campustrade.common.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class TransactionHooks {

    private static final Logger log = LoggerFactory.getLogger(TransactionHooks.class);

    private TransactionHooks() {
    }

    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            runSafely(action);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action);
            }
        });
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception exception) {
            log.warn("After-commit action failed", exception);
        }
    }
}
