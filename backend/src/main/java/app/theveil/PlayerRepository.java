package app.theveil;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PlayerRepository extends JpaRepository<PlayerEntity, UUID> {
  List<PlayerEntity> findByRoomIdOrderByJoinedAt(UUID roomId);
  Optional<PlayerEntity> findByTokenHash(String tokenHash);
  boolean existsByRoomIdAndDisplayNameIgnoreCase(UUID roomId, String displayName);
}
