package app.theveil;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="player_cards")
public class PlayerCardEntity {
  @Id public UUID id;
  @Column(name="room_id", nullable=false) public UUID roomId;
  @Column(name="player_id", nullable=false) public UUID playerId;
  @Column(name="card_id", nullable=false) public String cardId;
  @Column(name="dealt_at", nullable=false) public Instant dealtAt;
}
