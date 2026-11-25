package io.agentjava.auth;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import io.agentjava.store.Store;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final Store store;
  private final SecureRandom random = new SecureRandom();

  public AuthService(Store store) {
    this.store = store;
  }

  public static String digest(String v) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String password(String value, byte[] salt) {
    var p =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(65536)
            .withIterations(3)
            .withParallelism(1)
            .build();
    var gen = new Argon2BytesGenerator();
    gen.init(p);
    byte[] result = new byte[32];
    gen.generateBytes(value.toCharArray(), result);
    return Base64.getEncoder().encodeToString(result);
  }

  public Map<String, Object> register(Map<String, Object> req) {
    String email = s(req, "email").trim().toLowerCase(Locale.ROOT),
        pass = s(req, "password"),
        name = s(req, "displayName").trim();
    if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || pass.length() < 8
        || pass.length() > 256
        || name.isBlank()) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    String uid = id("user");
    try {
      store.db.update(
          "INSERT INTO users VALUES(?,?,?,?,?)",
          uid,
          email,
          name,
          Base64.getEncoder().encodeToString(salt) + ":" + password(pass, salt),
          now());
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      throw new ApiException("BUSINESS_CONFLICT");
    }
    return session(uid);
  }

  public Map<String, Object> login(Map<String, Object> req) {
    var rows =
        store.db.queryForList(
            "SELECT id,password_hash FROM users WHERE email=?",
            s(req, "email").trim().toLowerCase(Locale.ROOT));
    if (rows.isEmpty()) throw new ApiException("AUTH_INVALID_CREDENTIALS");
    String[] hash = str(rows.get(0).get("password_hash")).split(":");
    if (!MessageDigest.isEqual(
        hash[1].getBytes(StandardCharsets.UTF_8),
        password(s(req, "password"), Base64.getDecoder().decode(hash[0]))
            .getBytes(StandardCharsets.UTF_8))) throw new ApiException("AUTH_INVALID_CREDENTIALS");
    return session(str(rows.get(0).get("id")));
  }

  private Map<String, Object> session(String uid) {
    byte[] t = new byte[32];
    random.nextBytes(t);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
    store.db.update(
        "INSERT INTO auth_sessions VALUES(?,?,?,?,NULL)", digest(token), uid, now(), now());
    return obj("accessToken", token, "tokenType", "Bearer", "user", user(uid));
  }

  public Map<String, Object> current(String token) {
    var rows =
        store.db.queryForList(
            "SELECT user_id FROM auth_sessions WHERE token_hash=? AND revoked_at IS NULL",
            digest(token));
    if (rows.isEmpty()) throw new ApiException("AUTH_UNAUTHENTICATED");
    store.db.update(
        "UPDATE auth_sessions SET last_seen_at=? WHERE token_hash=?", now(), digest(token));
    return user(str(rows.get(0).get("user_id")));
  }

  public Map<String, Object> user(String uid) {
    var r =
        store.db.queryForMap("SELECT id,email,display_name,created_at FROM users WHERE id=?", uid);
    return obj(
        "id",
        uid,
        "email",
        r.get("email"),
        "displayName",
        r.get("display_name"),
        "createdAt",
        r.get("created_at"));
  }

  public void logout(String token) {
    store.db.update(
        "UPDATE auth_sessions SET revoked_at=? WHERE token_hash=?", now(), digest(token));
  }
}
