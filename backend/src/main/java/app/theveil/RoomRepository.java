package app.theveil;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface RoomRepository extends JpaRepository<RoomEntity, UUID> {
  Optional<RoomEntity> findByCode(String code);
  @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from RoomEntity r where r.code=:code") Optional<RoomEntity> lockByCode(@Param("code") String code);
}
