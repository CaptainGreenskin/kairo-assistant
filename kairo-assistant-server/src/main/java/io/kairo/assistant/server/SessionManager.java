package io.kairo.assistant.server;

import io.kairo.assistant.agent.AssistantSession;
import io.kairo.assistant.agent.ConversationStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionManager {

    private final AssistantSession assistantSession;
    private final ConcurrentHashMap<String, ClientSession> clientSessions = new ConcurrentHashMap<>();
    private final Path dataDir;

    public SessionManager(AssistantSession assistantSession) {
        this.assistantSession = assistantSession;
        this.dataDir = Path.of(assistantSession.config().dataDir());
    }

    public ClientSession getOrCreate(String clientId) {
        return clientSessions.computeIfAbsent(clientId, id -> {
            ConversationStore store = new ConversationStore(
                    dataDir.resolve("conversations").resolve("web"));
            store.startSession();
            return new ClientSession(id, store, Instant.now());
        });
    }

    public ClientSession get(String clientId) {
        return clientSessions.get(clientId);
    }

    public void remove(String clientId) {
        clientSessions.remove(clientId);
    }

    public Map<String, ClientSession> all() {
        return Map.copyOf(clientSessions);
    }

    public int activeCount() {
        return clientSessions.size();
    }

    public List<Map<String, Object>> activeSummary() {
        return clientSessions.values().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientId", s.clientId());
            m.put("connectedAt", s.connectedAt().toString());
            m.put("messageCount", s.messageCount());
            return m;
        }).toList();
    }

    public static final class ClientSession {
        private final String clientId;
        private final ConversationStore conversationStore;
        private final Instant connectedAt;
        private final AtomicInteger messageCount = new AtomicInteger(0);

        public ClientSession(String clientId, ConversationStore conversationStore, Instant connectedAt) {
            this.clientId = clientId;
            this.conversationStore = conversationStore;
            this.connectedAt = connectedAt;
        }

        public String clientId() { return clientId; }
        public ConversationStore conversationStore() { return conversationStore; }
        public Instant connectedAt() { return connectedAt; }
        public int incrementMessages() { return messageCount.incrementAndGet(); }
        public int messageCount() { return messageCount.get(); }
    }
}
