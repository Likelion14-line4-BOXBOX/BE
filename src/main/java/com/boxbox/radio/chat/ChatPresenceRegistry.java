package com.boxbox.radio.chat;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 단일 프로세스의 실제 연결 기반이다. 조회는 등록하지 않으며 SSE 계층이 연결/해제할 때 사용한다. */
@Component
public class ChatPresenceRegistry {
    private final Map<Long, Map<String, Set<UUID>>> rooms = new HashMap<>();

    public synchronized Connection connect(long roomId, String identity) {
        if (roomId <= 0 || identity == null || identity.isBlank()) throw new IllegalArgumentException("방과 연결 사용자 식별이 필요합니다.");
        UUID token = UUID.randomUUID();
        rooms.computeIfAbsent(roomId, ignored -> new HashMap<>()).computeIfAbsent(identity, ignored -> new HashSet<>()).add(token);
        return () -> disconnect(roomId, identity, token);
    }

    public synchronized Map<Long, Long> counts(Collection<Long> roomIds) {
        var result = new HashMap<Long, Long>();
        for (long id : roomIds) result.put(id, (long) rooms.getOrDefault(id, Map.of()).size());
        return Map.copyOf(result);
    }

    private synchronized void disconnect(long roomId, String identity, UUID token) {
        var users = rooms.get(roomId);
        if (users == null) return;
        var tokens = users.get(identity);
        if (tokens == null) return;
        tokens.remove(token);
        if (tokens.isEmpty()) users.remove(identity);
        if (users.isEmpty()) rooms.remove(roomId);
    }

    public interface Connection extends AutoCloseable {
        @Override void close();
    }
}
