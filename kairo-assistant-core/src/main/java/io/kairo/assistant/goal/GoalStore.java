package io.kairo.assistant.goal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoalStore {

    private static final Logger log = LoggerFactory.getLogger(GoalStore.class);

    private final Path storePath;
    private final ObjectMapper mapper;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Goal> goals = new ArrayList<>();

    public GoalStore(Path dataDir) {
        this.storePath = dataDir.resolve("goals.json");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        load();
    }

    public List<Goal> all() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(goals));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Goal> active() {
        lock.readLock().lock();
        try {
            return goals.stream()
                    .filter(g -> g.status() == Goal.GoalStatus.ACTIVE)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Goal> get(String id) {
        lock.readLock().lock();
        try {
            return goals.stream().filter(g -> g.id().equals(id)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void save(Goal goal) {
        lock.writeLock().lock();
        try {
            goals.removeIf(g -> g.id().equals(goal.id()));
            goals.add(goal);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean delete(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = goals.removeIf(g -> g.id().equals(id));
            if (removed) persist();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void update(Goal updated) {
        save(updated);
    }

    private void load() {
        if (!Files.exists(storePath)) return;
        try {
            List<Goal> loaded = mapper.readValue(storePath.toFile(), new TypeReference<>() {});
            goals.addAll(loaded);
            log.info("Loaded {} goals from {}", goals.size(), storePath);
        } catch (IOException e) {
            log.warn("Failed to load goals from {}: {}", storePath, e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), goals);
        } catch (IOException e) {
            log.error("Failed to persist goals to {}: {}", storePath, e.getMessage());
        }
    }
}
