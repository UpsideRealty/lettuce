package io.lettuce.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Non-blocking provider for connection objects. This connection provider is typed with a connection type and connection key
 * type.
 * <p>
 * {@link #getConnection(Object)} Connection requests} are synchronized with a shared {@link Sync synchronzer object} per
 * {@code ConnectionKey}. Multiple threads requesting a connection for the same {@code ConnectionKey} share the same
 * synchronizer and are not required to wait until a previous asynchronous connection is established but participate in existing
 * connection initializations. Shared synchronization leads to a fair synchronization amongst multiple threads waiting to obtain
 * a connection.
 *
 * @author Mark Paluch
 * @param <T> connection type.
 * @param <K> connection key type.
 * @since 5.1
 */
public class AsyncConnectionProvider<K, T extends AsyncCloseable> {

    private final Function<K, ? extends CompletionStage<T>> connectionFactory;

    private final Map<K, Sync<K, T>> connections = new ConcurrentHashMap<>();

    private volatile boolean closed;

    /**
     * Create a new {@link AsyncConnectionProvider}.
     *
     * @param connectionFactory must not be {@code null}.
     */
    @SuppressWarnings("unchecked")
    public AsyncConnectionProvider(Function<? extends K, ? extends CompletionStage<T>> connectionFactory) {

        LettuceAssert.notNull(connectionFactory, "AsyncConnectionProvider must not be null");
        this.connectionFactory = (Function<K, ? extends CompletionStage<T>>) connectionFactory;
    }

    /**
     * Request a connection for the given the connection {@code key} and return a {@link CompletionStage} that is notified about
     * the connection outcome.
     *
     * @param key the connection {@code key}, must not be {@code null}.
     * @return
     */
    public CompletableFuture<T> getConnection(K key) {
        return getSynchronizer(key).getConnection();
    }

    /**
     * Obtain a connection to a target given the connection {@code key}.
     *
     * @param key the connection {@code key}.
     * @return
     */
    private Sync<K, T> getSynchronizer(K key) {

        if (closed) {
            throw new IllegalStateException("ConnectionProvider is already closed");
        }

        Sync<K, T> sync = connections.get(key);

        if (sync != null) {
            return sync;
        }

        Sync<K, T> placeholder = new Sync<>(key);
        Sync<K, T> existing = connections.putIfAbsent(key, placeholder);

        if (existing != null) {
            return existing;
        }

        placeholder.getConnection().whenComplete((value, error) -> {
            if (error != null) {
                connections.remove(key, placeholder);
            }
        });

        if (closed) {
            connections.remove(key, placeholder);
            placeholder.completeExceptionally(new IllegalStateException("ConnectionProvider is already closed"));
            return placeholder;
        }

        try {
            CompletionStage<T> connection = connectionFactory.apply(key);
            LettuceAssert.notNull(connection, "ConnectionFuture must not be null");

            placeholder.attach(connection);
            return placeholder;
        } catch (Throwable t) {
            connections.remove(key, placeholder);
            placeholder.completeExceptionally(t);
            throw t;
        }
    }

    /**
     * Register a connection identified by {@code key}. Overwrites existing entries.
     *
     * @param key the connection {@code key}.
     * @param connection the connection object.
     */
    public void register(K key, T connection) {
        connections.put(key, new Sync<>(key, connection));
    }

    /**
     * @return number of established connections.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public int getConnectionCount() {

        Sync[] syncs = connections.values().toArray(new Sync[0]);
        int count = 0;

        for (Sync sync : syncs) {
            if (sync.isComplete()) {
                count++;
            }
        }

        return count;
    }

    /**
     * Close all connections. Pending connections are closed using future chaining.
     */
    public CompletableFuture<Void> close() {

        this.closed = true;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        connections.forEach((connectionKey, sync) -> {
            futures.add(sync.close());
            connections.remove(connectionKey, sync);
        });

        return Futures.allOf(futures);
    }

    /**
     * Close a connection by its connection {@code key}. Pending connections are closed using future chaining.
     *
     * @param key the connection {@code key}, must not be {@code null}.
     */
    public void close(K key) {

        LettuceAssert.notNull(key, "ConnectionKey must not be null!");

        Sync<K, T> sync = connections.remove(key);
        if (sync != null) {
            sync.close();
        }
    }

    /**
     * Execute an action for all established and pending connections.
     *
     * @param action the action.
     */
    public void forEach(Consumer<? super T> action) {

        LettuceAssert.notNull(action, "Action must not be null!");

        connections.values().forEach(sync -> {
            if (sync != null) {
                sync.doWithConnection(action);
            }
        });
    }

    /**
     * Execute an action for all established and pending {@link AsyncCloseable}s.
     *
     * @param action the action.
     */
    public void forEach(BiConsumer<? super K, ? super T> action) {
        connections.forEach((key, sync) -> sync.doWithConnection(action));
    }

    static class Sync<K, T extends AsyncCloseable> {

        private static final int PHASE_IN_PROGRESS = 0;

        private static final int PHASE_COMPLETE = 1;

        private static final int PHASE_FAILED = 2;

        private static final int PHASE_CANCELED = 3;

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private static final AtomicIntegerFieldUpdater<Sync> PHASE = AtomicIntegerFieldUpdater.newUpdater(Sync.class, "phase");

        // Updated with AtomicIntegerFieldUpdater
        @SuppressWarnings("unused")
        private volatile int phase = PHASE_IN_PROGRESS;

        private volatile T connection;

        private volatile CompletableFuture<T> delegate;

        private final K key;

        private final PlaceholderFuture future;

        private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

        private final AtomicBoolean closeRequested = new AtomicBoolean();

        private final AtomicBoolean closeStarted = new AtomicBoolean();

        public Sync(K key) {
            this.key = key;
            this.future = new PlaceholderFuture();
        }

        public Sync(K key, T value) {

            this.key = key;
            this.connection = value;
            this.future = new PlaceholderFuture();
            this.future.complete(value);
            PHASE.set(this, PHASE_COMPLETE);
        }

        public void attach(CompletionStage<T> future) {

            this.delegate = future.toCompletableFuture();

            future.whenComplete((connection, throwable) -> {

                if (throwable != null) {

                    if (throwable instanceof CancellationException) {
                        if (PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_CANCELED)) {
                            Sync.this.future.cancelInternal(false);
                        }
                    } else if (PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_FAILED)) {
                        Sync.this.future.completeExceptionally(throwable);
                    }

                    if (closeRequested.get()) {
                        closeFuture.complete(null);
                    }
                    return;
                }

                if (PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_COMPLETE)) {

                    if (connection != null) {
                        Sync.this.connection = connection;
                    }

                    Sync.this.future.complete(connection);

                    if (closeRequested.get()) {
                        closeConnection(connection);
                    }
                    return;
                }

                closeConnection(connection);
            });
        }

        public void completeExceptionally(Throwable throwable) {

            if (throwable instanceof CancellationException) {
                if (PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_CANCELED)) {
                    future.cancelInternal(false);
                }
            } else if (PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_FAILED)) {
                future.completeExceptionally(throwable);
            }

            if (closeRequested.get()) {
                closeFuture.complete(null);
            }
        }

        public CompletableFuture<T> getConnection() {
            return future;
        }

        public CompletableFuture<Void> close() {

            closeRequested.set(true);

            if (isComplete()) {
                closeConnection(connection);
            }

            return closeFuture;
        }

        void doWithConnection(Consumer<? super T> action) {

            if (isComplete()) {
                action.accept(connection);
            } else {
                future.thenAccept(action);
            }
        }

        void doWithConnection(BiConsumer<? super K, ? super T> action) {

            if (isComplete()) {
                action.accept(key, connection);
            } else {
                future.thenAccept(c -> action.accept(key, c));
            }
        }

        private void closeConnection(T connection) {

            if (connection == null) {
                closeFuture.complete(null);
                return;
            }

            if (!closeStarted.compareAndSet(false, true)) {
                return;
            }

            connection.closeAsync().whenComplete((unused, closeThrowable) -> {
                if (closeThrowable != null) {
                    closeFuture.completeExceptionally(closeThrowable);
                } else {
                    closeFuture.complete(null);
                }
            });
        }

        private boolean cancel(boolean mayInterruptIfRunning) {

            if (!PHASE.compareAndSet(this, PHASE_IN_PROGRESS, PHASE_CANCELED)) {
                return false;
            }

            CompletableFuture<T> delegate = this.delegate;
            if (delegate != null) {
                delegate.cancel(mayInterruptIfRunning);
            }

            return future.cancelInternal(mayInterruptIfRunning);
        }

        private boolean isComplete() {
            return PHASE.get(this) == PHASE_COMPLETE;
        }

        class PlaceholderFuture extends CompletableFuture<T> {

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return Sync.this.cancel(mayInterruptIfRunning);
            }

            boolean cancelInternal(boolean mayInterruptIfRunning) {
                return super.cancel(mayInterruptIfRunning);
            }

        }

    }

}
