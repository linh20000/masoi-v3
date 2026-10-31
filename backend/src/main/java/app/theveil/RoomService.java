package app.theveil;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static app.theveil.RoomDtos.*;

@Service
public class RoomService {
  private static final String ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private final RoomRepository rooms;
  private final PlayerRepository players;
  private final CardDefinitionRepository definitions;
  private final RoomCardRepository roomCards;
  private final PlayerCardRepository playerCards;
  private final SpecialRoleStateRepository specialStates;
  private final ApplicationEventPublisher events;
  private final SecureRandom random=new SecureRandom();
  private final ObjectMapper json=new ObjectMapper();

  public RoomService(RoomRepository rooms, PlayerRepository players, CardDefinitionRepository definitions,
      RoomCardRepository roomCards, PlayerCardRepository playerCards, SpecialRoleStateRepository specialStates, ApplicationEventPublisher events) {
    this.rooms=rooms; this.players=players; this.definitions=definitions; this.roomCards=roomCards; this.playerCards=playerCards; this.specialStates=specialStates; this.events=events;
  }

  @Transactional
  public SessionResponse create(CreateRequest request) {
    String hostName=validName(request.hostName());
    String token=secret(); RoomEntity room=new RoomEntity(); room.id=UUID.randomUUID(); room.hostName=hostName; room.hostTokenHash=hash(token);
    do { room.code=randomCode(); } while(rooms.findByCode(room.code).isPresent());
    room.status="LOBBY"; room.version=1; room.createdAt=Instant.now(); room.updatedAt=room.createdAt; rooms.save(room);
    return new SessionResponse(room.code,token,null,publicRoom(room,true));
  }

  @Transactional
  public SessionResponse join(String code, JoinRequest request) {
    RoomEntity room=lockedRoom(code); if(!"LOBBY".equals(room.status)) throw error(HttpStatus.CONFLICT,"ROOM_CLOSED","This room is no longer accepting players.");
    String name=validName(request.displayName());
    List<PlayerEntity> roster=players.findByRoomIdOrderByJoinedAt(room.id);
    PlayerEntity returning=roster.stream().filter(p->p.displayName.equalsIgnoreCase(name)).findFirst().orElse(null);
    if(returning!=null) {
      if(returning.connected) throw error(HttpStatus.CONFLICT,"NAME_TAKEN","That name is already in this room.");
      String token=secret();returning.tokenHash=hash(token);returning.connected=true;players.save(returning);changed(room);
      return new SessionResponse(room.code,token,returning.id.toString(),publicRoom(room,false));
    }
    if(roster.size()>=40) throw error(HttpStatus.CONFLICT,"ROOM_FULL","This room has reached its player limit.");
    String token=secret(); PlayerEntity player=new PlayerEntity(); player.id=UUID.randomUUID(); player.roomId=room.id; player.displayName=name;
    player.tokenHash=hash(token); player.connected=true; player.joinedAt=Instant.now(); players.save(player);
    changed(room); return new SessionResponse(room.code,token,player.id.toString(),publicRoom(room,false));
  }

  @Transactional(readOnly=true)
  public PublicRoom get(String code, String token) { RoomEntity r=room(code); authenticate(r,token); return publicRoom(r,isHost(r,token)); }

  @Transactional
  public void setCardRevealed(String code,String token,RevealRequest request) {
    RoomEntity r=lockedRoom(code);PlayerEntity p=playerSession(r,token);
    if(!"DEALT".equals(r.status)||playerCards.findByRoomIdAndPlayerId(r.id,p.id).isEmpty()) throw error(HttpStatus.CONFLICT,"CARD_NOT_DEALT","Your card is not currently dealt.");
    boolean revealed=request!=null&&request.revealed();
    boolean changedState=p.cardRevealed!=revealed;
    if(revealed&&!p.hostKnowsRole) { p.hostKnowsRole=true;changedState=true; }
    if(changedState) { p.cardRevealed=revealed;players.save(p);changed(r); }
  }

  @Transactional
  public PublicRoom updateDeck(String code,String token,DeckRequest request) {
    RoomEntity r=lockedRoom(code); requireHost(r,token);
    if(!"LOBBY".equals(r.status)) throw error(HttpStatus.CONFLICT,"SESSION_ACTIVE","Deck cannot change after dealing. Recall all cards first.");
    if(request==null||request.cards()==null) throw error(HttpStatus.BAD_REQUEST,"INVALID_DECK","Card list is required.");
    Set<String> ids=new HashSet<>(); int total=0;
    for(DeckItem item:request.cards()) {
      if(item==null||item.roleId()==null||item.count()<0||item.count()>40||!ids.add(item.roleId())) throw error(HttpStatus.BAD_REQUEST,"INVALID_DECK","Card entries must have unique roles and counts from 0 to 40.");
      if(!definitions.existsById(item.roleId())) throw error(HttpStatus.BAD_REQUEST,"UNKNOWN_ROLE","A selected role does not exist."); total+=item.count();
    }
    if(total>40) throw error(HttpStatus.BAD_REQUEST,"DECK_TOO_LARGE","The deck cannot contain more than 40 cards.");
    roomCards.deleteByRoomId(r.id);
    for(DeckItem item:request.cards()) if(item.count()>0) { RoomCardEntity e=new RoomCardEntity();e.roomId=r.id;e.cardId=item.roleId();e.quantity=item.count();roomCards.save(e); }
    changed(r); return publicRoom(r,true);
  }

  @Transactional
  public PublicRoom deal(String code,String token) {
    RoomEntity r=lockedRoom(code); requireHost(r,token);
    if("CLOSED".equals(r.status)) throw error(HttpStatus.CONFLICT,"ROOM_CLOSED","This session has ended.");
    if(!"LOBBY".equals(r.status)) throw error(HttpStatus.CONFLICT,"ALREADY_DEALT","Cards have already been dealt. Recall them before dealing again.");
    List<PlayerEntity> roster=players.findByRoomIdOrderByJoinedAt(r.id).stream().filter(p->p.connected).toList();
    if(roster.isEmpty()) throw error(HttpStatus.CONFLICT,"NO_PLAYERS","At least one player must join before dealing.");
    List<RoomCardEntity> selected=roomCards.findByRoomId(r.id); List<String> deck=new ArrayList<>();
    for(RoomCardEntity c:selected) for(int i=0;i<c.quantity;i++) deck.add(c.cardId);
    if(deck.size()!=roster.size()) throw error(HttpStatus.CONFLICT,"DECK_COUNT_MISMATCH","The selected deck contains "+deck.size()+" cards for "+roster.size()+" connected players. Set role quantities so the total matches the connected player count.");
    if(deck.isEmpty()) throw error(HttpStatus.CONFLICT,"EMPTY_DECK","Select cards before dealing.");
    Collections.shuffle(deck,random);
    for(int i=0;i<roster.size();i++) { PlayerCardEntity card=new PlayerCardEntity();card.id=UUID.randomUUID();card.roomId=r.id;card.playerId=roster.get(i).id;card.cardId=deck.get(i);card.dealtAt=Instant.now();playerCards.save(card);roster.get(i).cardRevealed=false;roster.get(i).hostKnowsRole=false;players.save(roster.get(i)); }
    r.status="DEALT"; changed(r); return publicRoom(r,true);
  }

  @Transactional
  public PublicRoom recall(String code,String token,RecallRequest request) {
    RoomEntity r=lockedRoom(code); requireHost(r,token);
    if("CLOSED".equals(r.status)) throw error(HttpStatus.CONFLICT,"ROOM_CLOSED","This session has ended.");
    List<UUID> target=request==null?null:request.playerIds();
    if(target==null||target.isEmpty()) { playerCards.deleteByRoomId(r.id);specialStates.deleteAll(specialStates.findByRoomId(r.id));players.findByRoomIdOrderByJoinedAt(r.id).forEach(p->{p.cardRevealed=false;p.hostKnowsRole=false;players.save(p);}); }
    else {
      Set<UUID> unique=new HashSet<>(target);
      Set<UUID> valid=players.findByRoomIdOrderByJoinedAt(r.id).stream().map(p->p.id).collect(Collectors.toSet());
      if(!valid.containsAll(unique)) throw error(HttpStatus.BAD_REQUEST,"PLAYER_NOT_IN_ROOM","One or more selected players are not in this room.");
      playerCards.deleteByRoomIdAndPlayerIdIn(r.id,unique);specialStates.deleteAll(specialStates.findByRoomId(r.id).stream().filter(s->unique.contains(s.playerId)).toList());players.findAllById(unique).forEach(p->{p.cardRevealed=false;p.hostKnowsRole=false;players.save(p);});
    }
    r.status=playerCards.findByRoomId(r.id).isEmpty()?"LOBBY":"DEALT"; changed(r); return publicRoom(r,true);
  }

  @Transactional(readOnly=true)
  public PrivateCard myCard(String code,String token) {
    RoomEntity r=room(code); PlayerEntity p=playerSession(r,token);if("CLOSED".equals(r.status)) throw error(HttpStatus.GONE,"ROOM_CLOSED","This session has ended.");
    PlayerCardEntity card=playerCards.findByRoomIdAndPlayerId(r.id,p.id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"CARD_NOT_DEALT","Your card has not been dealt yet."));
    Map<String,Object> state=new LinkedHashMap<>(specialState(r.id,p.id,card.cardId));
    String shownRole=stringValue(state.get("activeRoleId"));
    if(Set.of("actor","thief").contains(card.cardId)) {
      List<String> options=stringList(state.get("options"));List<String> facedown=new ArrayList<>();
      for(int i=0;i<options.size();i++)facedown.add(Integer.toString(i));
      String selected=stringValue(state.remove("selectedRoleId"));
      String active=stringValue(state.remove("activeRoleId"));
      String status=stringValue(state.get("status"));
      boolean confirmed=selected!=null&&active!=null&&("ACTIVE".equals(status)||"COMPLETED".equals(status));
      shownRole=confirmed?active:card.cardId;
      if(selected!=null)state.put("selectedOption",Integer.toString(options.indexOf(selected)));
      state.put("options",facedown);
    } else if(shownRole==null) shownRole=card.cardId;
    CardDefinitionEntity d=definitions.findById(shownRole).orElseThrow();return new PrivateCard(d.id,d.name,d.faction,d.description,d.imageUrl,card.cardId,state,p.cardRevealed);
  }

  @Transactional
  public void saveSpecialChoice(String code,String token,SpecialChoiceRequest request) {
    RoomEntity r=lockedRoom(code); PlayerEntity p=playerSession(r,token);
    if(!"DEALT".equals(r.status)) throw error(HttpStatus.CONFLICT,"SESSION_NOT_DEALT","Special choices are available during a dealt session.");
    if(request==null||request.roleId()==null||!Set.of("wild_child","half_wolf","actor","thief","shadow").contains(request.roleId())) throw error(HttpStatus.BAD_REQUEST,"UNKNOWN_SPECIAL_ROLE","This special role is not supported.");
    PlayerCardEntity card=playerCards.findByRoomIdAndPlayerId(r.id,p.id).orElseThrow(()->error(HttpStatus.CONFLICT,"CARD_NOT_DEALT","Special choices are available after dealing."));
    if(!card.cardId.equals(request.roleId())) throw error(HttpStatus.FORBIDDEN,"ROLE_MISMATCH","Only the holder of this role may submit its private choice.");
    Map<String,String> choice=request.choice()==null?Map.of():request.choice();
    if(choice.size()>4||choice.values().stream().anyMatch(v->v==null||v.length()>100)) throw error(HttpStatus.BAD_REQUEST,"INVALID_CHOICE","Choice payload is invalid.");
    SpecialRoleStateEntity state=specialStates.findByRoomIdAndPlayerIdAndRoleId(r.id,p.id,request.roleId()).orElseGet(()->{var s=new SpecialRoleStateEntity();s.id=UUID.randomUUID();s.roomId=r.id;s.playerId=p.id;s.roleId=request.roleId();return s;});
    Map<String,Object> saved=readState(state);
    if(Set.of("wild_child","shadow").contains(request.roleId())) {
      UUID targetId=validTarget(r,p,choice.get("target"));saved.put("targetPlayerId",targetId.toString());saved.put("status","CHOICE_SUBMITTED");
    } else if("half_wolf".equals(request.roleId())) {
      String faction=choice.get("faction");if(!Set.of("DÂN LÀNG","MA SÓI").contains(faction)) throw error(HttpStatus.BAD_REQUEST,"INVALID_FACTION","Choose Dân Làng or Ma Sói.");
      saved.put("faction",faction);saved.put("activeRoleId","MA SÓI".equals(faction)?"werewolf":"villager");saved.put("status","COMPLETED");
    } else if("thief".equals(request.roleId())) {
      String selected=resolveFacedownChoice(saved,choice.get("selectedOption"));List<String> options=stringList(saved.get("options"));
      if(!options.contains(selected)) throw error(HttpStatus.CONFLICT,"THIEF_OPTIONS_NOT_READY","The host has not prepared your two reserve cards yet.");
      saved.put("selectedRoleId",selected);saved.put("activeRoleId",selected);saved.put("status","COMPLETED");
    } else if("actor".equals(request.roleId())) {
      String selected=resolveFacedownChoice(saved,choice.get("selectedOption"));List<String> options=stringList(saved.get("options"));
      if("ACTIVE".equals(saved.get("status"))&&Objects.equals(stringValue(saved.get("selectedRoleId")),selected)) return;
      if("ACTIVE".equals(saved.get("status"))||"DEPLETED".equals(saved.get("status"))||options.isEmpty()||!options.contains(selected)) throw error(HttpStatus.CONFLICT,"ACTOR_NOT_ACTIVE","The host has not prepared an available Actor role card.");
      saved.put("selectedRoleId",selected);saved.put("activeRoleId",selected);saved.put("status","ACTIVE");
    } else throw error(HttpStatus.BAD_REQUEST,"UNKNOWN_SPECIAL_ROLE","This special role is not supported.");
    state.state=writeState(saved);
    state.updatedAt=Instant.now();specialStates.save(state);
    String activeRole=stringValue(saved.get("activeRoleId"));if(activeRole!=null&&!card.cardId.equals(activeRole)) { p.hostKnowsRole=true;players.save(p); }
    changed(r);
  }

  @Transactional(readOnly=true)
  public List<SpecialRoleStatus> specialRoleStatuses(String code,String token) {
    RoomEntity r=room(code);requireHost(r,token);
    List<SpecialRoleStatus> out=new ArrayList<>();
    for(PlayerCardEntity card:playerCards.findByRoomId(r.id)) {
      if(!Set.of("thief","wild_child","actor","half_wolf","shadow").contains(card.cardId)) continue;
      PlayerEntity p=players.findById(card.playerId).orElse(null);if(p==null)continue;
      Map<String,Object> state=specialState(r.id,p.id,card.cardId);String targetId=stringValue(state.get("targetPlayerId"));
      String targetName=targetId==null?null:players.findById(UUID.fromString(targetId)).map(x->x.displayName).orElse(null);
      String selected=stringValue(state.get("selectedRoleId"));String selectedName=selected==null?null:definitions.findById(selected).map(x->x.name).orElse(null);
      out.add(new SpecialRoleStatus(p.id.toString(),p.displayName,card.cardId,stringValue(state.get("status")),targetId,targetName,stringValue(state.get("faction")),selected,selectedName,stringList(state.get("options"))));
    }
    return out;
  }

  @Transactional
  public void hostSpecialAction(String code,String token,UUID playerId,SpecialRoleActionRequest request) {
    RoomEntity r=lockedRoom(code);requireHost(r,token);
    if(!"DEALT".equals(r.status)) throw error(HttpStatus.CONFLICT,"SESSION_NOT_DEALT","Special role actions are available after dealing.");
    if(request==null||request.roleId()==null||request.action()==null) throw error(HttpStatus.BAD_REQUEST,"INVALID_SPECIAL_ACTION","A role and action are required.");
    PlayerCardEntity card=playerCards.findByRoomIdAndPlayerId(r.id,playerId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"PLAYER_CARD_NOT_FOUND","That player has no dealt card."));
    if(!card.cardId.equals(request.roleId())) throw error(HttpStatus.CONFLICT,"ROLE_MISMATCH","The player no longer holds this special role.");
    SpecialRoleStateEntity entity=stateEntity(r.id,playerId,card.cardId);Map<String,Object> state=readState(entity);List<String> options=request.options()==null?List.of():request.options();
    switch(request.action()) {
      case "SET_THIEF_OPTIONS" -> { requireRole(card,"thief");validateOptions(options,2,Set.of("thief"));state.put("options",options);state.put("status","AWAITING_CHOICE"); }
      case "DRAW_THIEF_OPTIONS" -> { requireRole(card,"thief");List<String> pool=definitions.findAll().stream().map(d->d.id).filter(id->!"thief".equals(id)).collect(Collectors.toCollection(ArrayList::new));Collections.shuffle(pool,random);state.put("options",pool.subList(0,2));state.put("status","AWAITING_CHOICE"); }
      case "DRAW_ACTOR_OPTIONS" -> {
        requireRole(card,"actor");
        if(!stringList(state.get("options")).isEmpty()||"ACTIVE".equals(state.get("status"))||"DEPLETED".equals(state.get("status"))) throw error(HttpStatus.CONFLICT,"ACTOR_INVENTORY_ALREADY_PREPARED","The Actor inventory has already been drawn or depleted.");
        Set<String> active=roomCards.findByRoomId(r.id).stream().filter(x->x.quantity>0).map(x->x.cardId).collect(Collectors.toSet());
        List<String> pool=definitions.findAll().stream().map(d->d.id).filter(id->!"actor".equals(id)&&!active.contains(id)).collect(Collectors.toCollection(ArrayList::new));
        if(pool.size()<3) throw error(HttpStatus.CONFLICT,"ACTOR_INVENTORY_TOO_SMALL","At least three unused roles are required to draw the Actor inventory.");
        Collections.shuffle(pool,random);state.put("options",new ArrayList<>(pool.subList(0,3)));state.remove("selectedRoleId");state.remove("activeRoleId");state.put("status","AWAITING_CHOICE");
      }
      case "RECALL_ACTOR_CARD", "REVOKE_ACTOR" -> {
        requireRole(card,"actor");String used=stringValue(state.get("selectedRoleId"));List<String> inventory=new ArrayList<>(stringList(state.get("options")));
        if(!"ACTIVE".equals(state.get("status"))||used==null||!inventory.remove(used)) throw error(HttpStatus.CONFLICT,"ACTOR_CARD_NOT_ACTIVE","There is no active Actor card to recall.");
        state.put("options",inventory);state.remove("selectedRoleId");state.remove("activeRoleId");state.put("status",inventory.isEmpty()?"DEPLETED":"AWAITING_CHOICE");
      }
      case "TRANSFORM_WILD_CHILD" -> { requireRole(card,"wild_child");if(stringValue(state.get("targetPlayerId"))==null)throw error(HttpStatus.CONFLICT,"WILD_CHILD_IDOL_NOT_SET","The Wild Child has not selected an idol.");state.put("activeRoleId","werewolf");state.put("status","TRANSFORMED");players.findById(playerId).ifPresent(p->{p.hostKnowsRole=true;players.save(p);}); }
      case "TRANSFER_SHADOW" -> { requireRole(card,"shadow");String target=stringValue(state.get("targetPlayerId"));if(target==null)throw error(HttpStatus.CONFLICT,"SHADOW_TARGET_NOT_SET","The Shadow has not selected a target.");UUID targetId=UUID.fromString(target);PlayerCardEntity targetCard=playerCards.findByRoomIdAndPlayerId(r.id,targetId).orElseThrow(()->error(HttpStatus.CONFLICT,"TARGET_CARD_MISSING","The target has no role card to transfer."));state.put("activeRoleId",targetCard.cardId);state.put("selectedRoleId",targetCard.cardId);state.put("status","TRANSFERRED");players.findById(playerId).ifPresent(p->{p.hostKnowsRole=true;players.save(p);}); }
      default -> throw error(HttpStatus.BAD_REQUEST,"UNKNOWN_SPECIAL_ACTION","This special action is not supported.");
    }
    entity.state=writeState(state);entity.updatedAt=Instant.now();specialStates.save(entity);changed(r);
  }

  @Transactional(readOnly=true)
  public boolean validSession(String code,String token) { try { RoomEntity r=room(code);if("CLOSED".equals(r.status))return false;authenticate(r,token);return token!=null&&!token.isBlank(); } catch(RuntimeException e) { return false; } }

  @Transactional(readOnly=true)
  public boolean validRoom(String code) { try { return !"CLOSED".equals(room(code).status); } catch(RuntimeException e) { return false; } }

  @Transactional
  public void connectionChanged(String code,String token,boolean connected) {
    RoomEntity r=lockedRoom(code);
    if("CLOSED".equals(r.status)) return;
    if(token==null) return;
    if(MessageDigest.isEqual(r.hostTokenHash.getBytes(StandardCharsets.UTF_8),hash(token).getBytes(StandardCharsets.UTF_8))) return;
    String digest=hash(token);
    PlayerEntity p=players.findByRoomIdOrderByJoinedAt(r.id).stream().filter(x->x.tokenHash!=null&&MessageDigest.isEqual(x.tokenHash.getBytes(StandardCharsets.UTF_8),digest.getBytes(StandardCharsets.UTF_8))).findFirst().orElse(null);
    if(p==null) return; // A player may have rejoined with a rotated session token before an old socket closes.
    if(p.connected!=connected) { p.connected=connected;players.save(p);changed(r); }
  }

  @Transactional
  public void leave(String code,String token) {
    RoomEntity r=lockedRoom(code);PlayerEntity p=playerSession(r,token);
    if(p.connected) { p.connected=false;players.save(p);changed(r); }
  }

  @Transactional
  public PublicRoom endRoom(String code,String token) {
    RoomEntity r=lockedRoom(code);requireHost(r,token);
    if("CLOSED".equals(r.status)) throw error(HttpStatus.CONFLICT,"ROOM_CLOSED","This session has already ended.");
    r.status="CLOSED";changed(r);return publicRoom(r,true);
  }

  private SpecialRoleStateEntity stateEntity(UUID roomId,UUID playerId,String roleId) {
    return specialStates.findByRoomIdAndPlayerIdAndRoleId(roomId,playerId,roleId).orElseGet(()->{var s=new SpecialRoleStateEntity();s.id=UUID.randomUUID();s.roomId=roomId;s.playerId=playerId;s.roleId=roleId;s.updatedAt=Instant.now();return s;});
  }
  private Map<String,Object> specialState(UUID roomId,UUID playerId,String roleId) {
    return specialStates.findByRoomIdAndPlayerIdAndRoleId(roomId,playerId,roleId).map(this::readState).orElseGet(LinkedHashMap::new);
  }
  private Map<String,Object> readState(SpecialRoleStateEntity entity) {
    try { return json.readValue(entity.state,new TypeReference<>(){}); } catch(Exception e) { return new LinkedHashMap<>(); }
  }
  private String writeState(Map<String,Object> state) {
    try { return json.writeValueAsString(state); } catch(Exception e) { throw error(HttpStatus.BAD_REQUEST,"INVALID_SPECIAL_STATE","The special role state could not be saved."); }
  }
  private String stringValue(Object value) { return value instanceof String s&&!s.isBlank()?s:null; }
  private List<String> stringList(Object value) { return value instanceof List<?> list?list.stream().filter(String.class::isInstance).map(String.class::cast).toList():List.of(); }
  private String resolveFacedownChoice(Map<String,Object> state,String rawIndex) {
    try { int index=Integer.parseInt(rawIndex==null?"":rawIndex);List<String> options=stringList(state.get("options"));if(index>=0&&index<options.size())return options.get(index); }
    catch(NumberFormatException ignored) { }
    throw error(HttpStatus.BAD_REQUEST,"INVALID_CHOICE","Choose one of the available facedown cards.");
  }
  private UUID validTarget(RoomEntity room,PlayerEntity owner,String raw) {
    try { UUID id=UUID.fromString(raw==null?"":raw);if(id.equals(owner.id)||players.findById(id).filter(target->target.roomId.equals(room.id)).isEmpty())throw error(HttpStatus.BAD_REQUEST,"INVALID_TARGET","Choose another player in this room.");return id; }
    catch(IllegalArgumentException ex) { throw error(HttpStatus.BAD_REQUEST,"INVALID_TARGET","Choose another player in this room."); }
  }
  private void requireRole(PlayerCardEntity card,String roleId) { if(!roleId.equals(card.cardId))throw error(HttpStatus.CONFLICT,"ROLE_MISMATCH","The player does not hold this special role."); }
  private void validateOptions(List<String> options,int expected,Set<String> excluded) {
    if(options.size()!=expected||new HashSet<>(options).size()!=expected||options.stream().anyMatch(id->id==null||excluded.contains(id)||!definitions.existsById(id)))throw error(HttpStatus.BAD_REQUEST,"INVALID_ROLE_OPTIONS","Choose the required number of distinct, valid role cards.");
  }

  private PublicRoom publicRoom(RoomEntity r,boolean includeReveal) {
    List<PublicPlayer> roster=players.findByRoomIdOrderByJoinedAt(r.id).stream().map(p->{
      PlayerCardEntity card=playerCards.findByRoomIdAndPlayerId(r.id,p.id).orElse(null);
      boolean revealRole=includeReveal&&p.hostKnowsRole&&card!=null;
      String roleId=null,roleName=null;
      if(revealRole) {
        Map<String,Object> state=specialState(r.id,p.id,card.cardId);
        roleId=stringValue(state.get("activeRoleId"));if(roleId==null)roleId=card.cardId;
        roleName=definitions.findById(roleId).map(d->d.name).orElse(null);
      }
      return new PublicPlayer(p.id.toString(),p.displayName,p.connected,card!=null,includeReveal&&p.cardRevealed,includeReveal&&p.hostKnowsRole,roleId,roleName);
    }).toList();
    List<PublicDeck> deck=roomCards.findByRoomId(r.id).stream().map(c->{CardDefinitionEntity d=definitions.findById(c.cardId).orElseThrow();return new PublicDeck(d.id,d.name,d.faction,c.quantity);}).toList();
    return new PublicRoom(r.code,r.hostName,r.status,r.version,r.createdAt,roster,deck);
  }
  private boolean isHost(RoomEntity r,String token) { return token!=null&&MessageDigest.isEqual(r.hostTokenHash.getBytes(StandardCharsets.UTF_8),hash(token).getBytes(StandardCharsets.UTF_8)); }
  private RoomEntity room(String code) { return rooms.findByCode(normalizeCode(code)).orElseThrow(()->error(HttpStatus.NOT_FOUND,"ROOM_NOT_FOUND","Room code was not found.")); }
  private RoomEntity lockedRoom(String code) { return rooms.lockByCode(normalizeCode(code)).orElseThrow(()->error(HttpStatus.NOT_FOUND,"ROOM_NOT_FOUND","Room code was not found.")); }
  private void authenticate(RoomEntity r,String token) { if(token==null) throw error(HttpStatus.UNAUTHORIZED,"SESSION_REQUIRED","A room session is required."); String h=hash(token); if(r.hostTokenHash.equals(h)) return; if(players.findByRoomIdOrderByJoinedAt(r.id).stream().noneMatch(p->p.tokenHash.equals(h))) throw error(HttpStatus.FORBIDDEN,"SESSION_INVALID","This session cannot access the room."); }
  private void requireHost(RoomEntity r,String token) { if(token==null||!MessageDigest.isEqual(r.hostTokenHash.getBytes(StandardCharsets.UTF_8),hash(token).getBytes(StandardCharsets.UTF_8))) throw error(HttpStatus.FORBIDDEN,"HOST_ONLY","Only the host can perform this action."); }
  private PlayerEntity playerSession(RoomEntity r,String token) { if(token==null) throw error(HttpStatus.UNAUTHORIZED,"SESSION_REQUIRED","A player session is required."); String digest=hash(token);return players.findByRoomIdOrderByJoinedAt(r.id).stream().filter(p->p.tokenHash!=null&&MessageDigest.isEqual(p.tokenHash.getBytes(StandardCharsets.UTF_8),digest.getBytes(StandardCharsets.UTF_8))).findFirst().orElseThrow(()->error(HttpStatus.FORBIDDEN,"PLAYER_ONLY","A player session is required.")); }
  private void changed(RoomEntity r) { r.version++;r.updatedAt=Instant.now();rooms.save(r);events.publishEvent(new RoomChanged(r.code,r.status)); }
  private String validName(String name) { if(name==null||name.trim().length()<2||name.trim().length()>32) throw error(HttpStatus.BAD_REQUEST,"INVALID_NAME","Name must contain 2 to 32 characters."); return name.trim(); }
  private String normalizeCode(String code) { return code==null?"":code.trim().toUpperCase(Locale.ROOT); }
  private String randomCode() { StringBuilder s=new StringBuilder();for(int i=0;i<6;i++)s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));return s.toString(); }
  private String secret() { byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
  private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
  private ResponseStatusException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
  public static class ApiException extends ResponseStatusException { public final String code; public ApiException(HttpStatus s,String c,String m){super(s,m);code=c;} }
}
