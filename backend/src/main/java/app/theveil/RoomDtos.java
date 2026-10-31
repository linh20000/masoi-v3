package app.theveil;

import java.time.Instant;
import java.util.*;

public final class RoomDtos {
  private RoomDtos() {}
  public record CreateRequest(String hostName) {}
  public record JoinRequest(String displayName) {}
  public record DeckRequest(List<DeckItem> cards) {}
  public record DeckItem(String roleId, int count) {}
  public record RecallRequest(List<UUID> playerIds) {}
  public record SpecialChoiceRequest(String roleId, Map<String,String> choice) {}
  public record SessionResponse(String code, String sessionToken, String playerId, PublicRoom room) {}
  public record PublicPlayer(String id, String name, boolean connected, boolean hasCard, boolean revealed, boolean roleKnown, String revealedRoleId, String revealedRoleName) {}
  public record RevealRequest(boolean revealed) {}
  public record PublicDeck(String roleId, String name, String faction, int count) {}
  public record PublicRoom(String code, String hostName, String status, long version, Instant createdAt, List<PublicPlayer> players, List<PublicDeck> deck) {}
  public record PrivateCard(String roleId, String name, String faction, String description, String imageUrl, String sourceRoleId, Map<String,Object> specialState, boolean revealed) {}
  public record SpecialRoleActionRequest(String roleId, String action, List<String> options) {}
  public record SpecialRoleStatus(String playerId, String playerName, String roleId, String status, String targetPlayerId, String targetName, String faction, String selectedRoleId, String selectedRoleName, List<String> options) {}
  public record ApiError(String code, String message) {}
}
