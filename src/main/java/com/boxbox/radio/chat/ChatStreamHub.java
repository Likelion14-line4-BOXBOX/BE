package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 연결별 가상 스레드로 느린 수신자를 격리한다. 메모리 큐 대신 DB에서 최대 100개씩 읽는다. */
@Component
class ChatStreamHub {
    private final ChatStreamService service;
    private final ChatPresenceRegistry presence;
    private final Duration pollInterval;
    private final Duration heartbeat;
    private final Duration lifetime;
    private final Semaphore slots;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean stopped = new AtomicBoolean();

    ChatStreamHub(ChatStreamService service, ChatPresenceRegistry presence,
                  @Value("${boxbox.chat.stream-poll-interval:PT0.25S}") Duration pollInterval,
                  @Value("${boxbox.chat.stream-heartbeat:PT15S}") Duration heartbeat,
                  @Value("${boxbox.chat.stream-lifetime:PT5M}") Duration lifetime,
                  @Value("${boxbox.chat.stream-max-connections:256}") int maxConnections) {
        for (Duration value : List.of(pollInterval, heartbeat, lifetime)) {
            if (value.toMillis() < 1) throw new IllegalArgumentException("SSE 시간 설정은 1ms 이상이어야 합니다.");
        }
        if (maxConnections < 1) throw new IllegalArgumentException("SSE 연결 한도는 양수여야 합니다.");
        this.service = service;
        this.presence = presence;
        this.pollInterval = pollInterval;
        this.heartbeat = heartbeat;
        this.lifetime = lifetime;
        this.slots = new Semaphore(maxConnections);
    }

    SseEmitter subscribe(long roomId, String lastEventId, String loginId, String anonymousIdentity) {
        return subscribe(roomId, lastEventId, loginId, anonymousIdentity, () -> true);
    }

    SseEmitter subscribe(long roomId, String lastEventId, String loginId, String anonymousIdentity, BooleanSupplier credentialsValid) {
        if (!credentialsValid.getAsBoolean()) throw ApiException.unauthorized();
        var open = service.open(roomId, lastEventId, loginId);
        return start(roomId, loginId, open, anonymousIdentity, new SseEmitter(lifetime.toMillis()), credentialsValid);
    }

    SseEmitter start(long roomId, String loginId, ChatStreamService.Open open, String anonymousIdentity, SseEmitter emitter) {
        return start(roomId, loginId, open, anonymousIdentity, emitter, () -> true);
    }

    SseEmitter start(long roomId, String loginId, ChatStreamService.Open open, String anonymousIdentity, SseEmitter emitter, BooleanSupplier credentialsValid) {
        if (stopped.get() || !slots.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_STREAM_BUSY", "잠시 후 다시 연결해 주세요.");
        Client client;
        try {
            String identity = open.identity() == null ? "anonymous:" + anonymousIdentity : open.identity();
            if (open.identity() == null && (anonymousIdentity == null || anonymousIdentity.isBlank())) throw new IllegalArgumentException("익명 세션 식별자가 필요합니다.");
            client = new Client(roomId, loginId, open.sequence(), emitter, presence.connect(roomId, identity), credentialsValid);
        } catch (RuntimeException error) { slots.release(); throw error; }
        clients.add(client);
        emitter.onCompletion(client::release);
        emitter.onTimeout(client::finish);
        emitter.onError(error -> client.release());
        try {
            if (stopped.get()) client.finish();
            else workers.submit(client);
        } catch (RuntimeException error) { client.finish(); throw error; }
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        stopped.set(true);
        clients.forEach(Client::finish);
        workers.shutdownNow();
    }

    private final class Client implements Runnable {
        private final long roomId;
        private final String loginId;
        private final SseEmitter emitter;
        private final ChatPresenceRegistry.Connection connection;
        private final BooleanSupplier credentialsValid;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Thread thread;
        private long sequence;

        Client(long roomId, String loginId, long sequence, SseEmitter emitter, ChatPresenceRegistry.Connection connection, BooleanSupplier credentialsValid) {
            this.roomId = roomId;
            this.loginId = loginId;
            this.sequence = sequence;
            this.emitter = emitter;
            this.connection = connection;
            this.credentialsValid = credentialsValid;
        }

        @Override public void run() {
            thread = Thread.currentThread();
            long started = System.nanoTime();
            long lastHeartbeat = started;
            long online = -1;
            boolean ready = false;
            boolean participated = false;
            try {
                if (closed.get()) return;
                emitter.send(SseEmitter.event().comment("connected").reconnectTime(3000));
                while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                    if (!credentialsValid.getAsBoolean()) throw ApiException.unauthorized();
                    if (System.nanoTime() - started >= lifetime.toNanos()) {
                        control("RECONNECT_REQUIRED", Map.of("roomId", Long.toString(roomId), "reason", "CONNECTION_LIFETIME"));
                        break;
                    }
                    var batch = service.poll(roomId, sequence, loginId);
                    if (closed.get()) break;
                    if (!credentialsValid.getAsBoolean()) throw ApiException.unauthorized();
                    if (batch.resetRequired()) {
                        control("RESET_REQUIRED", Map.of("roomId", Long.toString(roomId), "reason", "EVENT_CURSOR_EXPIRED",
                                "historyUrl", "/api/chat-rooms/" + roomId + "/messages"));
                        break;
                    }
                    if (!ready) {
                        var checkpoint = new ChatStreamService.Envelope(ChatStreamService.cursor(roomId, sequence), Instant.now(),
                                Map.of("roomId", Long.toString(roomId)));
                        emitter.send(SseEmitter.event().id(checkpoint.eventId()).name("STREAM_READY").data(checkpoint, MediaType.APPLICATION_JSON));
                        ready = true;
                    }
                    if (!participated && loginId != null && batch.status() == ChatRoomState.LIVE && !closed.get()) {
                        // STREAM_READY 전송 실패·만료 커서·비회원은 지급하지 않는다.
                        // 대기 중 들어온 연결도 LIVE 전환 뒤 참여하며 메시지는 요구하지 않는다.
                        participated = service.participate(roomId, loginId, credentialsValid);
                    }
                    for (var event : batch.events()) {
                        if (closed.get()) break;
                        emitter.send(SseEmitter.event().id(event.data().eventId()).name(event.type()).data(event.data(), MediaType.APPLICATION_JSON));
                        sequence = event.sequence();
                    }
                    // 접속 수 이벤트는 영속 순번을 소비하지 않으며 재접속 시 현재 값으로 다시 보낸다.
                    long current = presence.counts(List.of(roomId)).get(roomId);
                    if (current != online) {
                        control("ONLINE_COUNT_CHANGED", Map.of("roomId", Long.toString(roomId), "onlineCount", current));
                        online = current;
                    }
                    if (sequence < batch.latest()) continue;
                    if (batch.status() == ChatRoomState.ARCHIVED) break;
                    if (System.nanoTime() - lastHeartbeat >= heartbeat.toNanos()) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastHeartbeat = System.nanoTime();
                    }
                    Thread.sleep(pollInterval.toMillis());
                }
                finish();
            } catch (IOException error) {
                // IOException의 async 완료는 서블릿 컨테이너가 맡는다. 여기서는 소유 자원만 정리한다.
                release();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                finish();
            } catch (RuntimeException error) {
                if (!closed.get()) {
                    try {
                        boolean unauthorized = error instanceof ApiException api && api.getStatus() == HttpStatus.UNAUTHORIZED;
                        control(unauthorized ? "AUTH_REQUIRED" : "STREAM_ERROR", Map.of("roomId", Long.toString(roomId),
                                "code", unauthorized ? "UNAUTHORIZED" : "CHAT_STREAM_UNAVAILABLE"));
                    } catch (IOException | RuntimeException ignored) { }
                }
                finish();
            } finally { release(); }
        }

        private void control(String type, Object payload) throws IOException {
            // id를 생략해 EventSource의 Last-Event-ID가 유실된 이벤트 뒤로 건너뛰지 않게 한다.
            emitter.send(SseEmitter.event().name(type).data(new ChatStreamService.Envelope(
                    ChatStreamService.cursor(roomId, sequence), Instant.now(), payload), MediaType.APPLICATION_JSON));
        }

        void finish() {
            if (release()) emitter.complete();
        }

        boolean release() {
            if (!closed.compareAndSet(false, true)) return false;
            connection.close();
            clients.remove(this);
            slots.release();
            Thread running = thread;
            if (running != null && running != Thread.currentThread()) running.interrupt();
            return true;
        }
    }
}
