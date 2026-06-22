package app.suggest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.shared.Suggestion;
import app.suggest.cache.RedisRouter;
import app.suggest.index.Index;
import app.suggest.index.IndexBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SuggestionServiceSingleFlightTest {

    private static final String PREFIX = "iph";
    private static final int CONCURRENCY = 64;
    private static final List<Suggestion> EXPECTED =
            List.of(new Suggestion("iphone", 9.0), new Suggestion("iphone case", 7.0));

    /**
     * A burst of concurrent requests at one cold prefix must collapse onto a single trie load.
     * The load is blocked until every caller has passed the cache check and attached to the
     * in-flight future, so a broken (per-request) load path would record more than one load.
     */
    @Test
    void concurrentMissesOnAColdPrefixTriggerExactlyOneTrieLoad() throws Exception {
        Index index = mock(Index.class);
        IndexBuilder indexBuilder = mock(IndexBuilder.class);
        RedisRouterStub router = new RedisRouterStub();

        when(indexBuilder.live()).thenReturn(index);
        when(index.generation()).thenReturn(1);

        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(index.topKFor(PREFIX)).thenAnswer(inv -> {
            loadStarted.countDown();
            releaseLoad.await(5, TimeUnit.SECONDS);
            return EXPECTED;
        });

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SuggestionService service = new SuggestionService(
                indexBuilder, router.instance(), new ObjectMapper(), metrics, 60, 15);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            Future<?>[] tasks = new Future<?>[CONCURRENCY];
            for (int i = 0; i < CONCURRENCY; i++) {
                tasks[i] = pool.submit(() -> {
                    start.await();
                    return service.suggest(PREFIX);
                });
            }

            start.countDown();
            assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // All callers have run the cache lookup and are about to attach to the in-flight
            // future; the leader is parked inside the load, so the future stays in the map.
            assertThat(router.allGetsObserved(5, TimeUnit.SECONDS)).isTrue();
            // Let any caller still between the lookup and computeIfAbsent get there before the
            // leader finishes and clears the entry.
            Thread.sleep(150);
            releaseLoad.countDown();

            for (Future<?> task : tasks) {
                @SuppressWarnings("unchecked")
                SuggestionService.Result result = (SuggestionService.Result) task.get(5, TimeUnit.SECONDS);
                assertThat(result.cacheHit()).isFalse();
                assertThat(result.suggestions()).isEqualTo(EXPECTED);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(index, times(1)).topKFor(PREFIX);
        assertThat(metrics.get("suggest.trie.loads").counter().count()).isEqualTo(1.0);
        assertThat(metrics.get("suggest.cache.misses").counter().count()).isEqualTo((double) CONCURRENCY);
        assertThat(router.writes()).isEqualTo(1);
    }

    /** A Mockito {@link RedisRouter} that always misses and counts cache-check / write traffic. */
    private static final class RedisRouterStub {
        private final RedisRouter mock = org.mockito.Mockito.mock(RedisRouter.class);
        private final CountDownLatch getsObserved = new CountDownLatch(CONCURRENCY);
        private volatile int writeCount;

        RedisRouterStub() {
            when(mock.get(eq(PREFIX), anyString())).thenAnswer(inv -> {
                getsObserved.countDown();
                return null;
            });
            // setEx returns void; record that exactly one populate happened.
            org.mockito.Mockito.doAnswer(inv -> {
                writeCount++;
                return null;
            }).when(mock).setEx(eq(PREFIX), anyString(), anyString(), anyLong());
        }

        RedisRouter instance() {
            return mock;
        }

        boolean allGetsObserved(long timeout, TimeUnit unit) throws InterruptedException {
            return getsObserved.await(timeout, unit);
        }

        int writes() {
            return writeCount;
        }
    }
}
