package com.wms.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 9.5：慢客户端保护。
 *
 * <p>压测时发现，一个不读 socket 的客户端会让 {@code sendMessage} 长时间阻塞，
 * 进而拖慢广播线程。这里用"发送耗时 3s"的会话模拟这种客户端，验证：
 * 超过 2s 的会话会被摘除并主动关闭，且发送方不会抛出异常。
 */
class WebSocketSlowClientTest {

    @Test
    void slowSessionIsDroppedAndClosedAfterSendTimeout() throws Exception {
        WebSocketSession raw = mock(WebSocketSession.class);
        when(raw.getId()).thenReturn("slow-1");
        when(raw.isOpen()).thenReturn(true);
        doAnswer(inv -> {
            Thread.sleep(3000);
            return null;
        }).when(raw).sendMessage(any(TextMessage.class));

        AgvWebSocketHandler handler = new AgvWebSocketHandler(new ObjectMapper());
        handler.afterConnectionEstablished(raw);
        assertEquals(1, handler.onlineCount());

        long begin = System.currentTimeMillis();
        assertDoesNotThrow(() -> handler.sendRaw("{\"type\":\"agv\",\"data\":{}}"));
        long cost = System.currentTimeMillis() - begin;

        assertTrue(cost >= 2500, "应观察到大致的发送阻塞，实际 " + cost + "ms");
        assertEquals(0, handler.onlineCount(), "慢客户端应从在线集合摘除");
        verify(raw).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void brokenSessionDoesNotThrow() {
        WebSocketSession raw = mock(WebSocketSession.class);
        when(raw.getId()).thenReturn("broken-1");
        when(raw.isOpen()).thenReturn(true);
        try {
            doAnswer(inv -> {
                throw new IllegalStateException("Message will not be sent because the WebSocket session has been closed");
            }).when(raw).sendMessage(any(TextMessage.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        AgvWebSocketHandler handler = new AgvWebSocketHandler(new ObjectMapper());
        handler.afterConnectionEstablished(raw);

        assertDoesNotThrow(() -> handler.sendRaw("{\"type\":\"agv\",\"data\":{}}"));
        assertEquals(0, handler.onlineCount());
    }
}
