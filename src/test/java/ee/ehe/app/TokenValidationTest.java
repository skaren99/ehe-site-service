package ee.ehe.app;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class TokenValidationTest {

  static final String ISSUER = "https://identity.example.test/realms/ehe";
  RSAKey key;
  HttpServer server;
  JwtDecoder decoder;

  @BeforeEach
  void setup() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] jwks = new JWKSet(key.toPublicJWK())
      .toString()
      .getBytes(StandardCharsets.UTF_8);
    server.createContext("/certs", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, jwks.length);
      exchange.getResponseBody().write(jwks);
      exchange.close();
    });
    server.start();
    decoder = new SecurityConfig().decoder(
      ISSUER,
      "http://127.0.0.1:" + server.getAddress().getPort() + "/certs",
      "ehe-site-api"
    );
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String token(
    String issuer,
    String audience,
    Instant expires,
    RSAKey signingKey
  ) throws Exception {
    var claims = new JWTClaimsSet.Builder()
      .subject("test-user")
      .issuer(issuer)
      .audience(audience)
      .issueTime(Date.from(Instant.now().minusSeconds(120)))
      .expirationTime(Date.from(expires))
      .build();
    var token = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
      claims
    );
    token.sign(new RSASSASigner(signingKey));
    return token.serialize();
  }

  @Test
  void validSignatureIssuerAndAudienceAreAccepted() throws Exception {
    assertEquals(
      "test-user",
      decoder
        .decode(
          token(ISSUER, "ehe-site-api", Instant.now().plusSeconds(300), key)
        )
        .getSubject()
    );
  }

  @Test
  void wrongAudienceIssuerExpiryAndSignatureAreRejected() throws Exception {
    assertThrows(JwtException.class, () ->
      decoder.decode(
        token(ISSUER, "another-api", Instant.now().plusSeconds(300), key)
      )
    );
    assertThrows(JwtException.class, () ->
      decoder.decode(
        token(
          "https://wrong.example.test",
          "ehe-site-api",
          Instant.now().plusSeconds(300),
          key
        )
      )
    );
    assertThrows(JwtException.class, () ->
      decoder.decode(
        token(ISSUER, "ehe-site-api", Instant.now().minusSeconds(90), key)
      )
    );
    RSAKey other = new RSAKeyGenerator(2048).keyID("test-key").generate();
    assertThrows(JwtException.class, () ->
      decoder.decode(
        token(ISSUER, "ehe-site-api", Instant.now().plusSeconds(300), other)
      )
    );
  }
}
