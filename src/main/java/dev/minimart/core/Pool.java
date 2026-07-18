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
public final class Pool {

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
                    real = DriverManager.getConnection(url, user, password);
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
        if (!real.isValid(1)) {                        // a dead one gets replaced, not handed out
            try { real.close(); } catch (SQLException ignored) {}
            real = DriverManager.getConnection(url, user, password);
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

    public int size() { return size; }
    public int idleCount() { return idle.size(); }
}
