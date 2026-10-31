package app.theveil;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final RoomService rooms;
  private final ObjectMapper mapper;
  @Value("${app.host-origin:http://localhost:5173}") private String origin;
  private final Map<String,Set<WebSocketSession>> sessions=new ConcurrentHashMap<>();
  public WebSocketConfig(RoomService rooms,ObjectMapper mapper) { this.rooms=rooms;this.mapper=mapper; }
  @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(new Handler(),"/ws/rooms/*").addInterceptors(new AuthHandshake()).setAllowedOriginPatterns(origin,"http://localhost:5173");
  }
  private class AuthHandshake implements HandshakeInterceptor {
    public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attrs) {
      String path=request.getURI().getPath(); String[] parts=path.split("/");
      String code=parts.length>0?parts[parts.length-1]:"";
      if(!rooms.validRoom(code)) return false; attrs.put("code",code.toUpperCase(Locale.ROOT)); return true;
    }
    public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler h,Exception e) {}
  }
  private class Handler extends TextWebSocketHandler {
    @Override protected void handleTextMessage(WebSocketSession s,TextMessage message) throws Exception {
      if(Boolean.TRUE.equals(s.getAttributes().get("authenticated"))) return;
      var auth=mapper.readTree(message.getPayload());String token=auth.path("token").asText("");String code=(String)s.getAttributes().get("code");
      if(!rooms.validRoom(code)) { s.close(new CloseStatus(4001,"ROOM_CLOSED"));return; }
      if(!"AUTH".equals(auth.path("type").asText())||!rooms.validSession(code,token)) { s.close(CloseStatus.NOT_ACCEPTABLE);return; }
      s.getAttributes().put("token",token);s.getAttributes().put("authenticated",true);sessions.computeIfAbsent(code,k->ConcurrentHashMap.newKeySet()).add(s);
      rooms.connectionChanged(code,token,true);s.sendMessage(new TextMessage("{\"type\":\"AUTHENTICATED\"}"));
    }
    @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status) {
      String code=(String)s.getAttributes().get("code");Set<WebSocketSession> roomSessions=sessions.get(code);if(roomSessions!=null){roomSessions.remove(s);if(roomSessions.isEmpty())sessions.remove(code,roomSessions);}
      String token=(String)s.getAttributes().get("token");if(token!=null&&!sessions.getOrDefault(code,Set.of()).stream().anyMatch(open->token.equals(open.getAttributes().get("token")))) rooms.connectionChanged(code,token,false);
    }
  }
  @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT) public void changed(RoomChanged event) {
    boolean closed="CLOSED".equals(event.status());String type=closed?"ROOM_CLOSED":"ROOM_UPDATED";
    for(WebSocketSession s:sessions.getOrDefault(event.code(),Set.of())) {
      try { synchronized(s) { if(s.isOpen()) s.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("type",type,"code",event.code())))); } } catch(Exception ignored) { }
      if(closed) try { synchronized(s) { if(s.isOpen()) s.close(new CloseStatus(4001,"ROOM_CLOSED")); } } catch(Exception ignored) { }
    }
  }
}
