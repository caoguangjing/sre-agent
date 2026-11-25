package io.agentjava.api;

import static io.agentjava.store.Json.*;

import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;

public final class Protocol {
  public static Map<String, Object> error(String code) {
    int status =
        switch (code) {
          case "AUTH_UNAUTHENTICATED", "AUTH_INVALID_CREDENTIALS", "AUTH_SESSION_REVOKED" -> 401;
          case "AUTH_FORBIDDEN" -> 403;
          case "BUSINESS_NOT_FOUND" -> 404;
          case "BUSINESS_CONFLICT", "CHAT_CONTEXT_LIMIT_REACHED" -> 409;
          case "VALIDATION_MISSING_FIELD" -> 422;
          case "VALIDATION_INVALID_ARGUMENT" -> 400;
          case "SYSTEM_UNAVAILABLE" -> 503;
          default -> 500;
        };
    String message =
        switch (code) {
          case "AUTH_FORBIDDEN" -> "无权访问此资源";
          case "AUTH_UNAUTHENTICATED" -> "请先登录";
          case "AUTH_INVALID_CREDENTIALS" -> "邮箱或密码错误";
          case "BUSINESS_CONFLICT" -> "操作与当前资源状态冲突";
          case "CHAT_CONTEXT_LIMIT_REACHED" -> "上下文已达到95%，请先手动压缩";
          case "SYSTEM_UNAVAILABLE" -> "外部服务暂不可用";
          default -> "请求无法完成";
        };
    return obj(
        "code",
        code,
        "category",
        code.equals("CHAT_CONTEXT_LIMIT_REACHED")
            ? "business"
            : code.split("_")[0].toLowerCase(Locale.ROOT),
        "httpStatus",
        status,
        "message",
        message);
  }

  public static void success(
      HttpServletRequest req, HttpServletResponse res, int status, Object data) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            encode(
                obj(
                    "ok",
                    true,
                    "data",
                    data,
                    "meta",
                    obj("requestId", req.getAttribute("requestId")))));
  }

  public static void failure(HttpServletRequest req, HttpServletResponse res, String code)
      throws IOException {
    var err = error(code);
    res.setStatus(n(err.get("httpStatus")));
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            encode(
                obj(
                    "ok",
                    false,
                    "error",
                    err,
                    "meta",
                    obj("requestId", req.getAttribute("requestId")))));
  }

  public static Map<String, Object> event(
      String channel, String type, Map<String, Object> payload) {
    var e = obj("id", id("evt"), "type", type, "channel", channel, "timestamp", now());
    e.putAll(payload);
    return e;
  }

  public interface Emitter {
    void emit(Map<String, Object> event);
  }

  public static Emitter stream(HttpServletResponse res) {
    res.setContentType("text/event-stream;charset=UTF-8");
    res.setHeader("Cache-Control", "no-cache");
    res.setHeader("X-Accel-Buffering", "no");
    return event -> {
      synchronized (res) {
        try {
          var writer = res.getWriter();
          writer.write(
              "id: "
                  + s(event, "id")
                  + "\nevent: "
                  + s(event, "type")
                  + "\ndata: "
                  + encode(event)
                  + "\n\n");
          writer.flush();
          if (writer.checkError()) throw new IllegalStateException("SSE 连接已断开");
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }
    };
  }
}
