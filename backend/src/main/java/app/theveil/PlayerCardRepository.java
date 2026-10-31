package app.theveil;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PlayerCardRepository extends JpaRepository<PlayerCardEntity, UUID> { List<PlayerCardEntity> findByRoomId(UUID roomId); Optional<PlayerCardEntity> findByRoomIdAndPlayerId(UUID roomId, UUID playerId); void deleteByRoomIdAndPlayerIdIn(UUID roomId, Collection<UUID> playerIds); void deleteByRoomId(UUID roomId); }
