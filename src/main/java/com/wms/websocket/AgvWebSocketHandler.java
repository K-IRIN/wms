package com.wms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGV 实时推送 WebSocket 通道（/ws/agv）
 * 向前端广播三类消息：agv（设备状态/位置）、task（任务状态）、alert（告警）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgvWebSocketHandler extends TextWebSocketHandler {

    /** 单条消息发送时间上限：超过即视为慢客户端（阶段 9.5） */
    private static final int SEND_TIME_LIMIT_MS = 2000;
    /** 单连接最大待发缓冲：写不出去的消息堆到这里，超限关闭连接（阶段 9.5） */
    private static final int SEND_BUFFER_SIZE = 512 * 1024;

    /** 在线连接（线程安全）：sessionId → 带并发/超时保护的装饰器 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 注入 Spring 配置好的 ObjectMapper（含 JavaTimeModule，可序列化 LocalDateTime） */
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 阶段 9.5：包一层 ConcurrentWebSocketSessionDecorator——慢客户端写入超过 2s
        // 或缓冲超过 512KB 时，由装饰器抛出 SessionLimitExceededException 并关闭连接，
        // 避免发送线程被一个不读 socket 的客户端长期占住。
        sessions.put(session.getId(),
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
    }

    /** 广播消息：{ "type": "agv|task|alert", "data": {...} } */
    public void broadcast(String type, Object data) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
        } catch (Exception e) {
            log.warn("AGV WS 消息序列化失败，已跳过：{}", e.getMessage());
            return;
        }
        sendRaw(json);
    }

    /**
     * 发送一条已序列化好的消息（阶段 9：由 {@code AgvPublisher} 的发送线程调用）。
     *
     * <p>慢客户端、半关闭连接都可能让 {@code sendMessage} 抛 {@link IOException} 或
     * {@link IllegalStateException}（"session has been closed"）；这里统一 catch 并摘除会话，
     * 保证单条发送失败不会把异常冒到调度线程里。
     */
    public void sendRaw(String json) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions.values()) {
            String id = session.getId();
            if (!session.isOpen()) {
                sessions.remove(id);
                continue;
            }
            long begin = System.nanoTime();
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (Exception e) {
                // IOException / IllegalStateException / SessionLimitExceededException 都可能是"连接不可用"，直接摘除
                sessions.remove(id);
                closeQuietly(session);
                continue;
            }
            // 阶段 9.5：单条消息写了超过 2s，说明客户端不读 socket。摘除并主动关闭，
            // 避免这条"坏连接"在后续每一轮广播里继续拖慢发送线程。
            // 不补发：客户端重连后由页面重新拉取全量快照。
            long costMs = (System.nanoTime() - begin) / 1_000_000L;
            if (costMs > SEND_TIME_LIMIT_MS) {
                sessions.remove(id);
                closeQuietly(session);
                log.warn("AGV WS 慢客户端已关闭：session={}，单条发送耗时 {}ms", id, costMs);
            }
        }
    }

    /** 关闭连接并忽略异常（连接可能已经半关闭） */
    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (Exception ignored) {
            // 已关闭/已断开，无需处理
        }
    }

    /** 当前在线连接数 */
    public int onlineCount() {
        return sessions.size();
    }
}
