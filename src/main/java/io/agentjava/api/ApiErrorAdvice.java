package io.agentjava.api;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrorAdvice {
  @ExceptionHandler(Exception.class)
  public void handle(Exception error, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    if (res.isCommitted()) return;
    String code =
        error instanceof ApiException a
            ? a.code
            : error instanceof MaxUploadSizeExceededException
                    || error instanceof IllegalArgumentException
                ? "VALIDATION_INVALID_ARGUMENT"
                : "SYSTEM_INTERNAL_ERROR";
    Protocol.failure(req, res, code);
  }
}
