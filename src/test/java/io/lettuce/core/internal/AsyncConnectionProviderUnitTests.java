package io.lettuce.core.internal;

import static io.lettuce.TestTags.UNIT_TEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AsyncConnectionProvider}.
 */
@Tag(UNIT_TEST)
class AsyncConnectionProviderUnitTests {

    @Test
    void shouldSharePlaceholderForSameThreadSameKeyRecursiveReentry() {

        AtomicInteger creations = new AtomicInteger();
        CompletableFuture<TestConnection> actualConnection = new CompletableFuture<>();
        AtomicReference<CompletableFuture<TestConnection>> recursiveLookup = new AtomicReference<>();

        AsyncConnectionProvider<String, TestConnection>[] holder = new AsyncConnectionProvider[1];
        holder[0] = new AsyncConnectionProvider<>(key -> {
            creations.incrementAndGet();
            recursiveLookup.set(holder[0].getConnection(key));
            return actualConnection;
        });

        CompletableFuture<TestConnection> firstLookup = holder[0].getConnection("key");

        assertThat(recursiveLookup.get()).isSameAs(firstLookup);
        assertThat(creations).hasValue(1);

        TestConnection connection = new TestConnection();
        actualConnection.complete(connection);

        assertThat(firstLookup).isCompletedWithValue(connection);
        assertThat(holder[0].getConnection("key")).isSameAs(firstLookup);
    }

    @Test
    void shouldSharePlaceholderForConcurrentSameKeyCallers() throws Exception {

        AtomicInteger creations = new AtomicInteger();
        CompletableFuture<TestConnection> actualConnection = new CompletableFuture<>();
        AsyncConnectionProvider<String, TestConnection> sut = new AsyncConnectionProvider<>(key -> {
            creations.incrementAndGet();
            return actualConnection;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CompletableFuture<TestConnection>> first = executor.submit(() -> sut.getConnection("key"));
            Future<CompletableFuture<TestConnection>> second = executor.submit(() -> sut.getConnection("key"));

            CompletableFuture<TestConnection> firstLookup = first.get(5, TimeUnit.SECONDS);
            CompletableFuture<TestConnection> secondLookup = second.get(5, TimeUnit.SECONDS);

            assertThat(firstLookup).isSameAs(secondLookup);
            assertThat(creations).hasValue(1);

            TestConnection connection = new TestConnection();
            actualConnection.complete(connection);

            assertThat(firstLookup).isCompletedWithValue(connection);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRemovePlaceholderAfterSynchronousFailure() {

        AtomicInteger attempts = new AtomicInteger();
        TestConnection connection = new TestConnection();

        AsyncConnectionProvider<String, TestConnection> sut = new AsyncConnectionProvider<>(key -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
            }

            return CompletableFuture.completedFuture(connection);
        });

        assertThatThrownBy(() -> sut.getConnection("key")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(sut.getConnection("key")).isCompletedWithValue(connection);
        assertThat(attempts).hasValue(2);
        assertThat(sut.getConnectionCount()).isEqualTo(1);
    }

    @Test
    void shouldRemovePlaceholderAfterAsynchronousFailure() {

        AtomicInteger attempts = new AtomicInteger();
        TestConnection connection = new TestConnection();

        AsyncConnectionProvider<String, TestConnection> sut = new AsyncConnectionProvider<>(key -> {
            if (attempts.getAndIncrement() == 0) {
                CompletableFuture<TestConnection> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("boom"));
                return failed;
            }

            return CompletableFuture.completedFuture(connection);
        });

        CompletableFuture<TestConnection> failedLookup = sut.getConnection("key");

        assertThatThrownBy(failedLookup::join).hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("boom");

        assertThat(sut.getConnection("key")).isCompletedWithValue(connection);
        assertThat(attempts).hasValue(2);
        assertThat(sut.getConnectionCount()).isEqualTo(1);
    }

    @Test
    void shouldCancelUnderlyingConnectionAttemptWhenLookupIsCancelled() {

        CompletableFuture<TestConnection> actualConnection = new CompletableFuture<>();
        AsyncConnectionProvider<String, TestConnection> sut = new AsyncConnectionProvider<>(key -> actualConnection);

        CompletableFuture<TestConnection> lookup = sut.getConnection("key");

        assertThat(lookup.cancel(false)).isTrue();
        assertThat(actualConnection).isCancelled();
    }

    @Test
    void shouldRetryAfterCancelledLookupAndCloseLateConnectionResult() {

        AtomicInteger attempts = new AtomicInteger();
        NonCancellableFuture<TestConnection> firstAttempt = new NonCancellableFuture<>();
        TestConnection firstConnection = new TestConnection();
        TestConnection secondConnection = new TestConnection();

        AsyncConnectionProvider<String, TestConnection> sut = new AsyncConnectionProvider<>(key -> {
            if (attempts.getAndIncrement() == 0) {
                return firstAttempt;
            }

            return CompletableFuture.completedFuture(secondConnection);
        });

        CompletableFuture<TestConnection> cancelledLookup = sut.getConnection("key");

        assertThat(cancelledLookup.cancel(false)).isTrue();

        CompletableFuture<TestConnection> retriedLookup = sut.getConnection("key");

        assertThat(retriedLookup).isCompletedWithValue(secondConnection);
        assertThat(attempts).hasValue(2);

        firstAttempt.complete(firstConnection);

        assertThat(firstConnection.closed).hasValue(1);
        assertThat(sut.getConnection("key")).isSameAs(retriedLookup);
    }

    @Test
    void closeShouldWaitForPendingConnectionAndCloseIt() {

        NonCancellableFuture<TestConnection> actualConnection = new NonCancellableFuture<>();
        AsyncConnectionProvider<String, TestConnection> sut = new AsyncConnectionProvider<>(key -> actualConnection);

        sut.getConnection("key");

        CompletableFuture<Void> closeFuture = sut.close();

        assertThat(closeFuture).isNotDone();

        TestConnection connection = new TestConnection();
        actualConnection.complete(connection);

        assertThat(closeFuture).isCompleted();
        assertThat(connection.closed).hasValue(1);
        assertThat(sut.getConnectionCount()).isEqualTo(0);
    }

    static class TestConnection implements AsyncCloseable {

        final AtomicInteger closed = new AtomicInteger();

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

    }

    static class NonCancellableFuture<T> extends CompletableFuture<T> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

    }

}
