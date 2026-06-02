package com.hmdp.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class TransactionUtils {

    private TransactionUtils() {
    }

    /**
     * 在当前事务提交成功后执行指定操作。
     * 如果当前有活跃的事务同步，注册 afterCommit 回调；
     * 否则直接执行（兼容非事务调用场景，与 ShopServiceImpl#deleteShopCacheAfterCommit 一致）。
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    }
            );
        } else {
            action.run();
        }
    }
}
