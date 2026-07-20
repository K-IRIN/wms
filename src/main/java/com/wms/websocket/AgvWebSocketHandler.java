package com.wms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGV 实时推送 WebSocket 通道（/ws/agv）
 * 向前端广播三类消息：agv（设备状态/位置）、task（任务状态）、alert（告警）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgvWebSocketHandler extends TextWebSocketHandler {

    /** 在线连接（线程安全） */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /** 注入 Spring 配置好的 ObjectMapper（含 JavaTimeModule，可序列化 LocalDateTime） */
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
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
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                sessions.remove(session);
            }
        }
    }

    /** 当前在线连接数 */
    public int onlineCount() {
        return sessions.size();
    }
}
