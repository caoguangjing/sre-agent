package io.agentjava.api;

public class ApiException extends RuntimeException {
  public final String code;

  public ApiException(String code) {
    super(code);
    this.code = code;
  }

  public ApiException(String code, String message) {
    super(message);
    this.code = code;
  }
}
