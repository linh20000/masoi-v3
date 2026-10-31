package app.theveil;

import jakarta.persistence.*;

@Entity @Table(name="card_definitions")
public class CardDefinitionEntity {
  @Id public String id;
  @Column(nullable=false) public String name;
  @Column(nullable=false) public String faction;
  @Column(nullable=false) public String description;
  @Column(name="image_url") public String imageUrl;
}
