package app.theveil;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RoomCardRepository extends JpaRepository<RoomCardEntity, RoomCardEntity.Key> { List<RoomCardEntity> findByRoomId(UUID roomId); void deleteByRoomId(UUID roomId); }
