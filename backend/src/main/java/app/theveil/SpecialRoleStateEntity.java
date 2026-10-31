package app.theveil;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="special_role_states")
public class SpecialRoleStateEntity {
  @Id public UUID id;
  @Column(name="room_id", nullable=false) public UUID roomId;
  @Column(name="player_id", nullable=false) public UUID playerId;
  @Column(name="role_id", nullable=false) public String roleId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false, columnDefinition="jsonb") public String state="{}";
  @Column(name="updated_at", nullable=false) public Instant updatedAt;
}
