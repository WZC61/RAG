package com.yizhaoqi.smartpai.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ChatSessionRegistry;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatWebSocketLogSafetyTest {
    @Test void connectionLogsIdentityWithoutPathCredential() throws Exception {
        var jwt=mock(JwtUtils.class);var session=mock(WebSocketSession.class);
        String token="credential-not-for-logs";
        when(jwt.validateToken(token)).thenReturn(true);when(jwt.extractUserIdFromToken(token)).thenReturn("1");
        when(session.getId()).thenReturn("socket-id");when(session.getUri()).thenReturn(URI.create("ws://localhost/chat/"+token));
        var handler=new ChatWebSocketHandler(mock(ChatHandler.class),jwt,mock(ChatSessionRegistry.class));
        Logger logger=(Logger) LoggerFactory.getLogger(ChatWebSocketHandler.class);
        var logs=new ListAppender<ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {handler.afterConnectionEstablished(session);
            assertTrue(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("socket-id")));
            assertTrue(logs.list.stream().noneMatch(e->e.getFormattedMessage().contains(token)));
        } finally {logger.detachAppender(logs);logs.stop();}
    }
}
