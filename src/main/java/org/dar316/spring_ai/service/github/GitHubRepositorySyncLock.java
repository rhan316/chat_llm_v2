package org.dar316.spring_ai.service.github;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Service
public class GitHubRepositorySyncLock {

    private final ConcurrentHashMap<String, LockEntry> entries = new ConcurrentHashMap<>();

    public <T> T withReadLock(String repository, String ref, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        String scope = scope(repository, ref);
        LockEntry entry = retain(scope);

        entry.lock.readLock().lock();

        try {
            return operation.get();
        } finally {
            entry.lock.readLock().unlock();
            release(scope, entry);
        }
    }

    public <T> T withWriteLock(String repository, String ref, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        String scope = scope(repository, ref);
        LockEntry entry = retain(scope);

        entry.lock.writeLock().lock();

        try {
            return operation.get();
        } finally {
            entry.lock.writeLock().unlock();
            release(scope, entry);
        }
    }

    private LockEntry retain(String scope) {
        return entries.compute(
                scope,
                (ignored, current) -> {
                    var entry = current == null ? new LockEntry() : current;
                    entry.users++;

                    return entry;
                }
        );
    }

    private void release(String scope, LockEntry entry) {
        entries.computeIfPresent(
                scope,
                (ignored, current) -> {
                    if (current != entry) {
                        return current;
                    }

                    current.users--;

                    if (current.users < 0) {
                        throw new IllegalStateException("GitHub repository lock usage count became negative");
                    }

                    return current.users == 0 ? null : current;
                }
        );
    }

    private String scope(String repository, String ref) {
        String normalizedRepository = requireNonBlank(repository, "repository");
        String normalizedRef = requireNonBlank(ref, "ref");

        return normalizedRepository + "\u0000"  + normalizedRef;
    }

    private String requireNonBlank(String value, String filedName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(filedName + " must not be blank");
        }

        return value.strip();
    }

    private static final class LockEntry {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private int users;
    }
}
