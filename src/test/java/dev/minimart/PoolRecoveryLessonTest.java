package dev.minimart;

import dev.minimart.core.Pool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE POOL MUST SURVIVE THE DATABASE COMING BACK.
 *
 * A connection pool is easy to write and easy to write in a way that works
 * perfectly until the one moment it matters. Every one of these lessons is
 * about the recovery path, which cannot be exercised against a database that
 * is working, which is exactly why it is the path that ships broken.
 *
 * The defect these were written for: a connection taken from the idle queue
 * has already been counted, and if validating or replacing it throws, it is
 * gone. Without decrementing the count with it, the pool permanently loses a
 * slot. Postgres restarting kills every pooled socket at once, so all of them
 * fail their replacement while the database is still starting, and afterwards
 * the pool believes it owns a full set of connections it does not have. Every
 * caller blocks for the timeout and fails, against a database that is healthy.
 *
 * The failure has the worst possible shape: the database is up, the service is
 * up, the logs say "no connection available", and nothing recovers it but a
 * restart.
 */
class PoolRecoveryLessonTest {

    /** A pool whose database can be told to fail, and whose connections can be
     *  told to go stale. No real database is involved: the whole point is the
     *  behaviour when there ISN'T one. */
    static final class TestPool extends Pool {
        final AtomicBoolean dbDown = new AtomicBoolean(false);
        final AtomicBoolean connectionsStale = new AtomicBoolean(false);
        final AtomicInteger opened = new AtomicInteger();

        TestPool(int size) { super("jdbc:test", "u", "p", size); }

        @Override
        protected Connection open() throws SQLException {
            if (dbDown.get()) throw new SQLException("connection refused");
            opened.incrementAndGet();
            return fake(connectionsStale);
        }
    }

    /**
     * LESSON 1 · A POOL THAT FAILED TO RECONNECT MUST STILL WORK AFTERWARDS.
     *
     * The database goes away, every pooled connection goes stale, and every
     * attempt to replace one fails. Then the database comes back. A pool that
     * did not return the slots is now permanently full of connections it does
     * not have.
     */
    @Test
    void lesson1_a_pool_recovers_once_the_database_does() throws Exception {
        TestPool pool = new TestPool(3);

        // fill it, the normal way
        Connection[] warm = new Connection[3];
        for (int i = 0; i < 3; i++) warm[i] = pool.borrow(1, TimeUnit.SECONDS);
        for (Connection c : warm) c.close();
        assertEquals(3, pool.created(), "three physical connections, all idle");

        // the database goes away and takes every socket with it
        pool.connectionsStale.set(true);
        pool.dbDown.set(true);
        for (int i = 0; i < 3; i++) {
            assertThrows(SQLException.class, () -> pool.borrow(50, TimeUnit.MILLISECONDS),
                    "with the database down, borrowing must fail");
        }
        assertEquals(0, pool.created(),
                "AND THE POOL MUST KNOW IT OWNS NOTHING, or it can never open a connection again");

        // the database comes back
        pool.dbDown.set(false);
        pool.connectionsStale.set(false);
        Connection recovered = assertDoesNotThrow(() -> pool.borrow(1, TimeUnit.SECONDS),
                "a healthy database must be reachable again without restarting the JVM");
        assertNotNull(recovered);
        recovered.close();
        System.out.println("lesson 1: 3 failed replacements, then the database returned and the pool worked");
    }

    /**
     * LESSON 2 · A STALE CONNECTION IS REPLACED WITHOUT LOSING A SLOT.
     *
     * The ordinary case of the same accounting: the connection is dead but the
     * database is fine, so it is swapped for a fresh one. The count must stay
     * where it was, or a pool in a system with idle timeouts bleeds slots
     * quietly over days and nobody connects the outage to the cause.
     */
    @Test
    void lesson2_replacing_a_stale_connection_keeps_the_count_straight() throws Exception {
        TestPool pool = new TestPool(2);
        Connection a = pool.borrow(1, TimeUnit.SECONDS);
        a.close();
        assertEquals(1, pool.created());

        pool.connectionsStale.set(true);
        Connection replaced = pool.borrow(1, TimeUnit.SECONDS);
        assertNotNull(replaced, "a stale connection is swapped, not handed out");
        assertEquals(1, pool.created(), "and the count did not drift");
        assertEquals(2, pool.opened.get(), "exactly one replacement was opened");
        replaced.close();

        // and it can still grow to its full size afterwards
        pool.connectionsStale.set(false);
        Connection one = pool.borrow(1, TimeUnit.SECONDS);
        Connection two = pool.borrow(1, TimeUnit.SECONDS);
        assertNotNull(two, "the second slot was never lost");
        one.close();
        two.close();
        System.out.println("lesson 2: a stale connection replaced, count steady, both slots still usable");
    }

    // ------------------------------------------------------------------ helpers

    /** A connection that exists only to answer isValid, close and getAutoCommit. */
    private static Connection fake(AtomicBoolean stale) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] args) {
                        return switch (m.getName()) {
                            case "isValid" -> !stale.get();
                            case "getAutoCommit" -> true;
                            case "close", "rollback", "setAutoCommit" -> null;
                            case "toString" -> "fake-connection";
                            case "hashCode" -> System.identityHashCode(p);
                            case "equals" -> p == args[0];
                            default -> null;
                        };
                    }
                });
    }
}
