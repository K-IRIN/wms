package com.wms.config;

import com.wms.utils.JwtUtils;
import com.wms.websocket.AgvWebSocketHandler;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 配置：注册 AGV 推送通道 /ws/agv
 * 浏览器 WebSocket 无法携带请求头，Token 通过查询参数 ?token=xxx 传递，握手时校验
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgvWebSocketHandler agvWebSocketHandler;
    private final JwtUtils jwtUtils;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agvWebSocketHandler, "/ws/agv")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        String query = request.getURI().getQuery();
                        String token = null;
                        if (query != null) {
                            for (String param : query.split("&")) {
                                int idx = param.indexOf('=');
                                if (idx > 0 && param.substring(0, idx).equals("token")) {
                                    token = param.substring(idx + 1);
                                }
                            }
                        }
                        if (token == null) {
                            return false;
                        }
                        try {
                            Claims claims = jwtUtils.parse(token);
                            attributes.put("userId", claims.get("userId", Long.class));
                            return true;
                        } catch (Exception e) {
                            // Token 无效或过期，拒绝握手
                            return false;
                        }
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {
                    }
                })
                .setAllowedOrigins("*");
    }
}
