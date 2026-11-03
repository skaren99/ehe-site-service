package ee.ehe.app;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(
      Map.of(
        "message",
        e.getReason() == null ? "Request failed" : e.getReason()
      )
    );
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest().body(
      Map.of("message", "Kontrolli sisestatud andmeid.")
    );
  }
}
