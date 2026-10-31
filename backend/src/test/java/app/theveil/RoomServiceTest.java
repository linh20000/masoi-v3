package app.theveil;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {
  @Mock RoomRepository rooms;
  @Mock PlayerRepository players;
  @Mock CardDefinitionRepository definitions;
  @Mock RoomCardRepository roomCards;
  @Mock PlayerCardRepository playerCards;
  @Mock SpecialRoleStateRepository specialStates;
  @Mock ApplicationEventPublisher events;
  RoomService service;
  RoomEntity room;
  PlayerEntity first;
  PlayerEntity second;

  @BeforeEach void setup() {
    service=new RoomService(rooms,players,definitions,roomCards,playerCards,specialStates,events);
    room=new RoomEntity();room.id=UUID.randomUUID();room.code="ABCD23";room.hostName="Host";room.hostTokenHash=hash("host-secret");room.status="LOBBY";room.version=2;room.createdAt=Instant.now();room.updatedAt=room.createdAt;
    first=player("One");second=player("Two");
  }

  @Test void dealAssignsOnePrivateCardPerConnectedPlayerAndNeverPutsRolesInPublicRoster() {
    when(rooms.lockByCode(room.code)).thenReturn(Optional.of(room));
    when(players.findByRoomIdOrderByJoinedAt(room.id)).thenReturn(List.of(first,second));
    when(roomCards.findByRoomId(room.id)).thenReturn(List.of(deck("villager",1),deck("seer",1)));
    when(definitions.findById("villager")).thenReturn(Optional.of(definition("villager","Dân làng")));
    when(definitions.findById("seer")).thenReturn(Optional.of(definition("seer","Tiên tri")));
    List<PlayerCardEntity> assignedCards=new ArrayList<>();
    when(playerCards.save(any())).thenAnswer(call->{PlayerCardEntity card=call.getArgument(0);assignedCards.add(card);return card;});
    when(playerCards.findByRoomIdAndPlayerId(room.id,first.id)).thenAnswer(call->assignedCards.stream().filter(c->c.playerId.equals(first.id)).findFirst());
    when(playerCards.findByRoomIdAndPlayerId(room.id,second.id)).thenAnswer(call->assignedCards.stream().filter(c->c.playerId.equals(second.id)).findFirst());

    RoomDtos.PublicRoom result=service.deal(room.code,"host-secret");

    assertEquals("DEALT",result.status());
    assertEquals(3,result.version());
    assertTrue(result.players().stream().allMatch(RoomDtos.PublicPlayer::hasCard));
    assertEquals(List.of("id","name","connected","hasCard"),Arrays.stream(RoomDtos.PublicPlayer.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    ArgumentCaptor<PlayerCardEntity> assigned=ArgumentCaptor.forClass(PlayerCardEntity.class);
    verify(playerCards,times(2)).save(assigned.capture());
    assertEquals(Set.of(first.id,second.id),new HashSet<>(assigned.getAllValues().stream().map(c->c.playerId).toList()));
    assertEquals(Set.of("villager","seer"),new HashSet<>(assigned.getAllValues().stream().map(c->c.cardId).toList()));
  }

  @Test void dealRejectsDeckCountMismatchWithoutWritingPartialCards() {
    when(rooms.lockByCode(room.code)).thenReturn(Optional.of(room));
    when(players.findByRoomIdOrderByJoinedAt(room.id)).thenReturn(List.of(first,second));
    when(roomCards.findByRoomId(room.id)).thenReturn(List.of(deck("villager",1)));
    RoomService.ApiException error=assertThrows(RoomService.ApiException.class,()->service.deal(room.code,"host-secret"));
    assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
    assertEquals("DECK_COUNT_MISMATCH",error.code);
    assertEquals("LOBBY",room.status);
    verify(playerCards,never()).save(any());
  }

  @Test void playerCannotDealCards() {
    when(rooms.lockByCode(room.code)).thenReturn(Optional.of(room));
    RoomService.ApiException error=assertThrows(RoomService.ApiException.class,()->service.deal(room.code,"not-the-host"));
    assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
    assertEquals("HOST_ONLY",error.code);
    verify(players,never()).findByRoomIdOrderByJoinedAt(any());
  }

  @Test void createReturnsOneTimeHostSecretAndStoresOnlyItsDigest() {
    RoomDtos.SessionResponse result=service.create(new RoomDtos.CreateRequest("New Host"));
    assertNotNull(result.sessionToken());
    assertNotEquals(result.sessionToken(),hash(result.sessionToken()));
    assertEquals(6,result.code().length());
    ArgumentCaptor<RoomEntity> saved=ArgumentCaptor.forClass(RoomEntity.class);
    verify(rooms).save(saved.capture());
    assertEquals(hash(result.sessionToken()),saved.getValue().hostTokenHash);
  }

  @Test void joinIssuesAnIndependentSessionAndChangesRoomVersion() {
    when(rooms.lockByCode(room.code)).thenReturn(Optional.of(room));
    when(players.existsByRoomIdAndDisplayNameIgnoreCase(room.id,"Guest")).thenReturn(false);
    when(players.findByRoomIdOrderByJoinedAt(room.id)).thenReturn(List.of());
    RoomDtos.SessionResponse result=service.join(room.code,new RoomDtos.JoinRequest("Guest"));
    assertEquals(room.code,result.code());
    assertNotNull(result.playerId());
    assertNotNull(result.sessionToken());
    assertEquals(3,result.room().version());
    ArgumentCaptor<PlayerEntity> saved=ArgumentCaptor.forClass(PlayerEntity.class);
    verify(players).save(saved.capture());
    assertEquals(hash(result.sessionToken()),saved.getValue().tokenHash);
  }

  @Test void recallAllReturnsRoomToLobbyWithoutRemovingPlayers() {
    when(rooms.lockByCode(room.code)).thenReturn(Optional.of(room));
    when(players.findByRoomIdOrderByJoinedAt(room.id)).thenReturn(List.of(first,second));
    when(playerCards.findByRoomId(room.id)).thenReturn(List.of());
    when(roomCards.findByRoomId(room.id)).thenReturn(List.of());
    RoomDtos.PublicRoom result=service.recall(room.code,"host-secret",new RoomDtos.RecallRequest(null));
    assertEquals("LOBBY",result.status());
    assertEquals(2,result.players().size());
    verify(playerCards).deleteByRoomId(room.id);
  }

  @Test void endingRoomIsTerminalAndDistinctFromRecall() {
    when(rooms.lockByCode(room.code)).thenReturn(Optional.of(room));
    when(players.findByRoomIdOrderByJoinedAt(room.id)).thenReturn(List.of(first));
    when(roomCards.findByRoomId(room.id)).thenReturn(List.of());
    when(playerCards.findByRoomIdAndPlayerId(room.id,first.id)).thenReturn(Optional.empty());
    RoomDtos.PublicRoom ended=service.endRoom(room.code,"host-secret");
    assertEquals("CLOSED",ended.status());
    RoomService.ApiException error=assertThrows(RoomService.ApiException.class,()->service.recall(room.code,"host-secret",new RoomDtos.RecallRequest(null)));
    assertEquals("ROOM_CLOSED",error.code);
    verify(playerCards,never()).deleteByRoomId(room.id);
  }

  @Test void playerReadsOnlyTheirOwnDealtCard() {
    first.tokenHash=hash("player-secret");
    second.tokenHash=hash("other-secret");
    PlayerCardEntity card=new PlayerCardEntity();card.id=UUID.randomUUID();card.roomId=room.id;card.playerId=first.id;card.cardId="seer";
    PlayerCardEntity secondCard=new PlayerCardEntity();secondCard.id=UUID.randomUUID();secondCard.roomId=room.id;secondCard.playerId=second.id;secondCard.cardId="villager";
    when(rooms.findByCode(room.code)).thenReturn(Optional.of(room));
    when(players.findByRoomIdOrderByJoinedAt(room.id)).thenReturn(List.of(first,second));
    when(playerCards.findByRoomIdAndPlayerId(room.id,first.id)).thenReturn(Optional.of(card));
    when(playerCards.findByRoomIdAndPlayerId(room.id,second.id)).thenReturn(Optional.of(secondCard));
    when(definitions.findById("seer")).thenReturn(Optional.of(definition("seer","Tiên tri")));
    when(definitions.findById("villager")).thenReturn(Optional.of(definition("villager","Dân làng")));
    assertEquals("seer",service.myCard(room.code,"player-secret").roleId());
    assertEquals("villager",service.myCard(room.code,"other-secret").roleId());
    RoomService.ApiException error=assertThrows(RoomService.ApiException.class,()->service.myCard(room.code,"not-a-player"));
    assertEquals("PLAYER_ONLY",error.code);
  }

  private PlayerEntity player(String name) { PlayerEntity p=new PlayerEntity();p.id=UUID.randomUUID();p.roomId=room.id;p.displayName=name;p.connected=true;p.joinedAt=Instant.now();return p; }
  private RoomCardEntity deck(String id,int count) { RoomCardEntity c=new RoomCardEntity();c.roomId=room.id;c.cardId=id;c.quantity=count;return c; }
  private CardDefinitionEntity definition(String id,String name) { CardDefinitionEntity d=new CardDefinitionEntity();d.id=id;d.name=name;d.faction="DÂN LÀNG";d.description="Mô tả";d.imageUrl="";return d; }
  private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new RuntimeException(e); } }
}
