package app.theveil;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SpecialRoleStateRepository extends JpaRepository<SpecialRoleStateEntity,UUID> {
  Optional<SpecialRoleStateEntity> findByRoomIdAndPlayerIdAndRoleId(UUID roomId,UUID playerId,String roleId);
  List<SpecialRoleStateEntity> findByRoomId(UUID roomId);
}
