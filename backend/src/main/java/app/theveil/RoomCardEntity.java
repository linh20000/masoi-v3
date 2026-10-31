package app.theveil;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.UUID;

@Entity @Table(name="room_cards") @IdClass(RoomCardEntity.Key.class)
public class RoomCardEntity {
  @Id @Column(name="room_id") public UUID roomId;
  @Id @Column(name="card_id") public String cardId;
  @Column(nullable=false) public int quantity;
  public static class Key implements Serializable { public UUID roomId; public String cardId; public Key() {} }
}
