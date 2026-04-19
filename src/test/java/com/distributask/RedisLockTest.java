package com.distributask;

import com.distributask.lock.RedisDistributedLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisDistributedLock.
 *
 * We mock StringRedisTemplate so these tests run without a real Redis server.
 * The integration behaviour (true SETNX atomicity across multiple JVMs)
 * is verified by running the full docker-compose stack and observing
 * execution_history.worker_node values in the database.
 */
class RedisLockTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisDistributedLock lock;

    @BeforeEach
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps      = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        lock = new RedisDistributedLock(redisTemplate, new SimpleMeterRegistry());
    }

    @Test
    void acquireLock_returnsTrue_whenKeyDoesNotExist() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        assertTrue(lock.acquireLock(42L, 30));
    }

    @Test
    void acquireLock_returnsFalse_whenKeyAlreadyExists() {
        // Simulates another worker holding the lock (setIfAbsent returns false)
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        assertFalse(lock.acquireLock(42L, 30));
    }

    @Test
    void acquireLock_handlesNullResponse_safely() {
        // Redis client can return null on connectivity issues
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(null);

        assertFalse(lock.acquireLock(42L, 30));
    }

    @Test
    void releaseLock_deletesKey_whenOwnerMatches() {
        String workerNode = lock.getWorkerNode();
        when(valueOps.get("job:lock:42")).thenReturn(workerNode);

        lock.releaseLock(42L);

        verify(redisTemplate).delete("job:lock:42");
    }

    @Test
    void releaseLock_doesNotDelete_whenOwnerMismatch() {
        when(valueOps.get("job:lock:42")).thenReturn("some-other-worker");

        lock.releaseLock(42L);

        verify(redisTemplate, never()).delete(anyString());
    }
}
