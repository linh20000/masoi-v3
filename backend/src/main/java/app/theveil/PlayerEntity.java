package app.theveil;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="players")
public class PlayerEntity {
  @Id public UUID id;
  @Column(name="room_id", nullable=false) public UUID roomId;
  @Column(name="display_name", nullable=false) public String displayName;
  @Column(name="token_hash", nullable=false, length=64) public String tokenHash;
  @Column(nullable=false) public boolean connected=true;
  @Column(name="card_revealed", nullable=false) public boolean cardRevealed=false;
  @Column(name="host_knows_role", nullable=false) public boolean hostKnowsRole=false;
  @Column(name="joined_at", nullable=false) public Instant joinedAt;
}
