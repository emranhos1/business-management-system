package com.eh.bms.util;

import com.eh.bms.config.DatabaseConfig;
import org.hibernate.Session;
import org.hibernate.Transaction;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs work in one atomic DB transaction: commit on success, rollback on any error.
 * Use for every financial operation.
 */
public final class TransactionHelper {

    private TransactionHelper() {
    }

    public static <T> T inTransaction(Function<Session, T> work) {
        try (Session session = DatabaseConfig.getSessionFactory().openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                T result = work.apply(session);
                tx.commit();
                return result;
            } catch (RuntimeException e) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw e;
            }
        }
    }

    public static void runInTransaction(Consumer<Session> work) {
        inTransaction(session -> {
            work.accept(session);
            return null;
        });
    }
}
