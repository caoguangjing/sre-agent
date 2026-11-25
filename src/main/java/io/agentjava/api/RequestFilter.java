package io.agentjava.api;

import static io.agentjava.store.Json.*;

import io.agentjava.auth.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestFilter extends OncePerRequestFilter {
  private final AuthService auth;
  public final LongAdder requests = new LongAdder(),
      failures = new LongAdder(),
      duration = new LongAdder();

  public RequestFilter(AuthService auth) {
    this.auth = auth;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    String rid = req.getHeader("X-Request-ID");
    if (rid == null || !rid.matches("[a-zA-Z0-9_.:-]{1,128}")) rid = id("request");
    req.setAttribute("requestId", rid);
    res.setHeader("X-Request-ID", rid);
    String origin = req.getHeader("Origin");
    if (origin != null && origin.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]+")) {
      res.setHeader("Access-Control-Allow-Origin", origin);
      res.setHeader("Vary", "Origin");
      res.setHeader(
          "Access-Control-Allow-Headers", "Authorization,Content-Type,X-Request-ID,Last-Event-ID");
      res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
      res.setHeader("Access-Control-Expose-Headers", "X-Request-ID");
    }
    if (req.getMethod().equals("OPTIONS")) {
      res.setStatus(204);
      return;
    }
    try {
      String path = req.getRequestURI();
      if (!Set.of(
              "/health",
              "/ready",
              "/config/check",
              "/health/mcp",
              "/metrics",
              "/auth/register",
              "/auth/login",
              "/error",
              "/openapi.json",
              "/aiops/alerts/sunfire")
          .contains(path)) {
        String bearer = req.getHeader("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer "))
          throw new ApiException("AUTH_UNAUTHENTICATED");
        var user = auth.current(bearer.substring(7));
        req.setAttribute("user", user);
        req.setAttribute("owner", s(user, "id"));
      }
      chain.doFilter(req, res);
    } catch (ApiException e) {
      if (!res.isCommitted()) Protocol.failure(req, res, e.code);
    } finally {
      requests.increment();
      duration.add((System.nanoTime() - start) / 1_000_000);
      if (res.getStatus() >= 400) failures.increment();
      org.slf4j.LoggerFactory.getLogger(RequestFilter.class)
          .atInfo()
          .addKeyValue("event", "http.complete")
          .addKeyValue("requestId", rid)
          .addKeyValue("path", req.getRequestURI())
          .addKeyValue("status", res.getStatus())
          .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
          .log("http.complete");
    }
  }
}
