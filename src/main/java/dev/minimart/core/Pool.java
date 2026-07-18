package dev.minimart.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small connection pool, written by hand.
 *
 * It exists because the naive version broke in a way worth remembering: with a
 * connection opened per ledger call, a few hundred simulated customers
 * exhausted the machine's ephemeral TCP ports and connections started failing
 * with "Address already in use". Those failures then made a seeded run
 * IRREPRODUCIBLE, because which call failed depended on socket timing. A
 * missing pool did not merely make things slow, it destroyed determinism.
 *
 * borrow() hands out a PROXY whose close() returns the connection instead of
 * closing it, so every existing try-with-resources keeps working unchanged.
 * On return the connection is rolled back and reset, so no state leaks to the
 * next borrower. An empty pool makes the caller wait: bounded queueing is
 * backpressure, not collapse.
 */
/* Not final: open() is overridable so a test can make the database fail on
 * demand. The recovery path is the one that matters and it cannot be
 * exercised against a database that is working. */
public class Pool {

    private final String url, user, password;
    private final ArrayBlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();
    private final int size;

    public Pool(String url, String user, String password, int size) {
        this.url = url; this.user = user; this.password = password; this.size = size;
        this.idle = new ArrayBlockingQueue<>(size);
    }

    public Connection borrow(long timeout, TimeUnit unit) throws SQLException {
        Connection real = idle.poll();
        if (real == null && created.get() < size) {
            synchronized (this) {
                if (created.get() < size) {
                    real = open();
                    created.incrementAndGet();
                }
            }
        }
        if (real == null) {
            try {
                real = idle.poll(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted waiting for a connection", e);
            }
            if (real == null) throw new SQLException("no connection available within " + timeout + " " + unit);
        }
        // A DEAD ONE GETS REPLACED, NOT HANDED OUT.
        //
        // The accounting here is the whole point, and the first version got it
        // wrong in a way that only bites during recovery. A connection taken
        // from idle has already been counted in `created`. If validating or
        // replacing it throws, it is gone, and unless `created` comes down with
        // it, the pool permanently loses a slot.
        //
        // Postgres restarting kills every pooled socket at once, so all `size`
        // slots fail their replacement while the database is still coming up.
        // After that `created == size` with `idle` empty forever: the guard
        // above can never open another connection, every caller blocks for the
        // timeout and fails, and the database is perfectly healthy throughout.
        // Nothing recovers it but a restart, which is the worst shape an outage
        // can have, because everything anybody would think to look at says fine.
        //
        // The two failures are handled separately rather than under one catch,
        // because they differ in who still owns the slot. The second version of
        // this fix used a single catch and decremented twice on the path where
        // both happen, driving the count negative.
        boolean valid;
        try {
            valid = real.isValid(1);
        } catch (SQLException e) {
            // the connection is still counted, so it is ours to give back
            try { real.close(); } catch (SQLException ignored) {}
            created.decrementAndGet();
            throw e;
        }
        if (!valid) {
            try { real.close(); } catch (SQLException ignored) {}
            // The slot goes back BEFORE the replacement is attempted, so if
            // open() throws, the count is already correct and the exception
            // needs no cleanup of its own.
            created.decrementAndGet();
            Connection fresh = open();
            created.incrementAndGet();
            real = fresh;
        }
        return proxy(real);
    }

    private Connection proxy(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    private boolean returned = false;
                    @Override public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                        switch (m.getName()) {
                            case "close" -> {
                                if (!returned) {
                                    returned = true;
                                    release(real);
                                }
                                return null;
                            }
                            case "isClosed" -> { return returned; }
                            default -> {
                                if (returned) throw new SQLException("connection already returned to the pool");
                                try { return m.invoke(real, args); }
                                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                            }
                        }
                    }
                });
    }

    /** Reset before reuse: an unfinished transaction must never reach the next borrower. */
    private void release(Connection real) {
        try {
            if (!real.getAutoCommit()) { real.rollback(); real.setAutoCommit(true); }
            if (!idle.offer(real)) real.close();
        } catch (SQLException e) {
            try { real.close(); } catch (SQLException ignored) {}
            created.decrementAndGet();
        }
    }

    /** Open one physical connection. Overridable so a test can make the
     *  database fail on demand: the recovery path is the one that matters and
     *  it cannot be exercised against a database that is working. */
    protected Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** How many physical connections this pool believes it owns. If this ever
     *  sticks at size with nothing idle, the pool is dead and this is the
     *  number that says so. */
    public int created() { return created.get(); }

    public int size() { return size; }
    public int idleCount() { return idle.size(); }
}
