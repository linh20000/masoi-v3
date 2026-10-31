package app.theveil;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static app.theveil.RoomDtos.*;

@RestController @RequestMapping("/api/rooms")
public class RoomController {
  private final RoomService service;
  public RoomController(RoomService service) { this.service=service; }
  @PostMapping public ResponseEntity<SessionResponse> create(@RequestBody CreateRequest r) { return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r)); }
  @PostMapping("/{code}/join") public ResponseEntity<SessionResponse> join(@PathVariable String code,@RequestBody JoinRequest r) { return ResponseEntity.status(HttpStatus.CREATED).body(service.join(code,r)); }
  @PostMapping("/{code}/leave") public ResponseEntity<Void> leave(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth) { service.leave(code,bearer(auth));return ResponseEntity.noContent().build(); }
  @GetMapping("/{code}") public PublicRoom get(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth) { return service.get(code,bearer(auth)); }
  @PutMapping("/{code}/deck") public PublicRoom deck(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody DeckRequest r) { return service.updateDeck(code,bearer(auth),r); }
  @PostMapping("/{code}/deal") public PublicRoom deal(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth) { return service.deal(code,bearer(auth)); }
  @PostMapping("/{code}/recall") public PublicRoom recall(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody(required=false) RecallRequest r) { return service.recall(code,bearer(auth),r); }
  @PostMapping("/{code}/end") public PublicRoom end(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth) { return service.endRoom(code,bearer(auth)); }
  @GetMapping("/{code}/me") public PrivateCard me(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth) { return service.myCard(code,bearer(auth)); }
  @PostMapping("/{code}/card-reveal") public ResponseEntity<Void> reveal(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody RevealRequest r) { service.setCardRevealed(code,bearer(auth),r);return ResponseEntity.noContent().build(); }
  @PostMapping("/{code}/special-choice") public ResponseEntity<Void> special(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody SpecialChoiceRequest r) { service.saveSpecialChoice(code,bearer(auth),r);return ResponseEntity.noContent().build(); }
  @GetMapping("/{code}/special-roles") public java.util.List<SpecialRoleStatus> specialRoles(@PathVariable String code,@RequestHeader(value="Authorization",required=false) String auth) { return service.specialRoleStatuses(code,bearer(auth)); }
  @PostMapping("/{code}/special-roles/{playerId}") public ResponseEntity<Void> specialAction(@PathVariable String code,@PathVariable java.util.UUID playerId,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody SpecialRoleActionRequest r) { service.hostSpecialAction(code,bearer(auth),playerId,r);return ResponseEntity.noContent().build(); }
  private String bearer(String value) { return value!=null&&value.startsWith("Bearer ")?value.substring(7):null; }
}
