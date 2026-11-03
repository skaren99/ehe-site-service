package ee.ehe.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SiteApiTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  StateStore store;

  @Autowired
  ObjectMapper mapper;

  @BeforeEach
  void reset() {
    store.change(s -> {
      s.putArray("bookings");
      s.putArray("closures");
      s.put("revision", 0);
      ((ObjectNode) s.withArray("products").get(0)).put("stock", 1);
      return null;
    });
  }

  org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_SITE_ADMIN"));
  }

  String start() {
    return LocalDate.now(ZoneId.of("Europe/Tallinn"))
      .plusDays(30)
      .atTime(10, 0)
      .atZone(ZoneId.of("Europe/Tallinn"))
      .toInstant()
      .toString();
  }

  ObjectNode booking(String key) {
    return mapper
      .createObjectNode()
      .put("productId", "veil-1")
      .put("start", start())
      .put("quantity", 1)
      .put("extraDays", 0)
      .put("name", "Test")
      .put("email", "test@example.com")
      .put("requestKey", key);
  }

  @Test
  void publicReadAndAdminSecurity() throws Exception {
    mvc.perform(get("/api/site/public/products")).andExpect(status().isOk());
    mvc
      .perform(get("/api/site/admin/state"))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        get("/api/site/admin/state").with(
          jwt().authorities(new SimpleGrantedAuthority("ROLE_PLANNER"))
        )
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(get("/api/site/admin/state").with(admin()))
      .andExpect(status().isOk());
  }

  @Test
  void bookingIsIdempotentAndRejectsConflicts() throws Exception {
    var body = booking(UUID.randomUUID().toString());
    String first = mvc
      .perform(
        post("/api/site/public/bookings")
          .contentType("application/json")
          .content(body.toString())
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.total").value(70))
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        post("/api/site/public/bookings")
          .contentType("application/json")
          .content(body.toString())
      )
      .andExpect(status().isCreated())
      .andExpect(content().json(first));
    body.put("email", "changed@example.com");
    mvc
      .perform(
        post("/api/site/public/bookings")
          .contentType("application/json")
          .content(body.toString())
      )
      .andExpect(status().isConflict());
    body.put("requestKey", UUID.randomUUID().toString());
    mvc
      .perform(
        post("/api/site/public/bookings")
          .contentType("application/json")
          .content(body.toString())
      )
      .andExpect(status().isConflict());
    assertEquals(1, store.read().withArray("bookings").size());
  }

  @Test
  void simultaneousBookingsCannotOversell() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch go = new CountDownLatch(1);
      Callable<Integer> send = () -> {
        go.await();
        return mvc
          .perform(
            post("/api/site/public/bookings")
              .contentType("application/json")
              .content(booking(UUID.randomUUID().toString()).toString())
          )
          .andReturn()
          .getResponse()
          .getStatus();
      };
      Future<Integer> a = pool.submit(send),
        b = pool.submit(send);
      go.countDown();
      assertEquals(
        Set.of(201, 409),
        Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))
      );
      assertEquals(1, store.read().withArray("bookings").size());
    }
  }

  @Test
  void inclusiveClosureBlocksReservedPeriod() throws Exception {
    LocalDate date = Instant.parse(start())
      .atZone(ZoneId.of("Europe/Tallinn"))
      .toLocalDate()
      .plusDays(1);
    String body = mapper
      .createObjectNode()
      .put("kind", "rental")
      .put("productId", "all")
      .put("from", date.toString())
      .put("to", date.toString())
      .toString();
    mvc
      .perform(
        post("/api/site/admin/closures")
          .with(admin())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk());
    mvc
      .perform(
        post("/api/site/public/bookings")
          .contentType("application/json")
          .content(booking(UUID.randomUUID().toString()).toString())
      )
      .andExpect(status().isConflict());
  }

  @Test
  void productUpdatesRequireCurrentRevisionAndValidShape() throws Exception {
    ObjectNode body = store.read();
    body.put("revision", 99);
    mvc
      .perform(
        put("/api/site/admin/products")
          .with(admin())
          .contentType("application/json")
          .content(body.toString())
      )
      .andExpect(status().isConflict());
    body.put("revision", 0);
    ((ObjectNode) body.withArray("products").get(0)).put("stock", -1);
    mvc
      .perform(
        put("/api/site/admin/products")
          .with(admin())
          .contentType("application/json")
          .content(body.toString())
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void settingsRejectMalformedPricing() throws Exception {
    ObjectNode body = store.read();
    ((ObjectNode) body.path("settings")).putObject("pricing");
    mvc
      .perform(
        put("/api/site/admin/settings")
          .with(admin())
          .contentType("application/json")
          .content(body.toString())
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void corsDoesNotAllowUnlistedOrigins() throws Exception {
    mvc
      .perform(
        options("/api/site/admin/state")
          .header("Origin", "https://untrusted.example")
          .header("Access-Control-Request-Method", "GET")
      )
      .andExpect(status().isForbidden());
  }
}
