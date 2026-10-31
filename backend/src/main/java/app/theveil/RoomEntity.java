package app.theveil;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="rooms")
public class RoomEntity {
  @Id public UUID id;
  @Column(nullable=false, unique=true, length=6) public String code;
  @Column(name="host_name", nullable=false) public String hostName;
  @Column(name="host_token_hash", nullable=false, length=64) public String hostTokenHash;
  @Column(nullable=false) public String status="LOBBY";
  @Column(nullable=false) public long version=0;
  @Column(name="created_at", nullable=false) public Instant createdAt;
  @Column(name="updated_at", nullable=false) public Instant updatedAt;
}
