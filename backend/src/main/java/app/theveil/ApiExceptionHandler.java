package app.theveil;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static app.theveil.RoomDtos.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(RoomService.ApiException.class)
  public ResponseEntity<ApiError> api(RoomService.ApiException e) { return ResponseEntity.status(e.getStatusCode()).body(new ApiError(e.code,e.getReason())); }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception e) { return ResponseEntity.status(500).body(new ApiError("INTERNAL_ERROR","The request could not be completed.")); }
}
