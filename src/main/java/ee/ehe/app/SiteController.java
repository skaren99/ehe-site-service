package ee.ehe.app;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/site")
public class SiteController {

  private final StateStore store;
  private final ObjectMapper mapper;

  SiteController(StateStore store, ObjectMapper mapper) {
    this.store = store;
    this.mapper = mapper;
  }

  record BookingInput(
    @NotBlank String productId,
    @NotNull Instant start,
    @Min(1) @Max(1000) int quantity,
    @Min(0) @Max(30) int extraDays,
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Email @Size(max = 200) String email,
    @NotNull UUID requestKey
  ) {}

  record ClosureInput(
    @Pattern(regexp = "event|rental") @NotNull String kind,
    @NotBlank String productId,
    @NotNull LocalDate from,
    @NotNull LocalDate to
  ) {}

  @GetMapping("/public/settings")
  public JsonNode settings() {
    return store.read().path("settings");
  }

  @GetMapping("/public/products")
  public List<JsonNode> products() {
    List<JsonNode> out = new ArrayList<>();
    store
      .read()
      .withArray("products")
      .forEach(p -> {
        if (p.path("stock").asInt() > 0) out.add(p);
      });
    return out;
  }

  @GetMapping("/public/events")
  public List<JsonNode> events() {
    List<JsonNode> out = new ArrayList<>();
    store
      .read()
      .withArray("closures")
      .forEach(c -> {
        if (c.path("kind").asText().equals("event")) out.add(c);
      });
    return out;
  }

  @GetMapping("/admin/state")
  public ObjectNode state() {
    return store.read();
  }

  @PutMapping("/admin/settings")
  public JsonNode settings(@RequestBody JsonNode body) {
    return store.change(s -> {
      checkRevision(s, body);
      JsonNode value = body.path("settings");
      validateSettings(value);
      s.set("settings", value.deepCopy());
      bump(s);
      return s;
    });
  }

  @PutMapping("/admin/products")
  public ObjectNode products(@RequestBody JsonNode body) {
    return store.change(s -> {
      checkRevision(s, body);
      JsonNode ps = body.path("products");
      if (!ps.isArray() || ps.size() > 500) bad();
      Set<String> ids = new HashSet<>();
      for (JsonNode p : ps) {
        if (
          !p.path("id").asText().matches("[a-z0-9-]{1,80}") ||
          !ids.add(p.path("id").asText()) ||
          !p.path("price").isNumber() ||
          p.path("price").decimalValue().signum() < 0 ||
          p.path("price").asDouble() > 1000000 ||
          !p.path("stock").isIntegralNumber() ||
          p.path("stock").asInt() < 0 ||
          p.path("stock").asInt() > 10000 ||
          !List.of("table", "large", "clothes").contains(
            p.path("category").asText()
          )
        ) bad();
        for (String lang : List.of("et", "en")) {
          bounded(p.path("name").path(lang), 150, true);
          bounded(p.path("description").path(lang), 10000, false);
        }
      }
      s.set("products", ps.deepCopy());
      bump(s);
      return s;
    });
  }

  @PostMapping("/admin/closures")
  public ObjectNode closure(@Valid @RequestBody ClosureInput input) {
    if (input.to().isBefore(input.from())) bad();
    return store.change(s -> {
      if (
        input.kind().equals("rental") && !input.productId().equals("all")
      ) product(s, input.productId());
      ObjectNode c = mapper.valueToTree(input);
      c.put("id", UUID.randomUUID().toString());
      s.withArray("closures").add(c);
      bump(s);
      return s;
    });
  }

  @DeleteMapping("/admin/closures/{id}")
  public ObjectNode removeClosure(@PathVariable String id) {
    return store.change(s -> {
      ArrayNode array = s.withArray("closures");
      for (int i = 0; i < array.size(); i++) {
        if (array.get(i).path("id").asText().equals(id)) {
          array.remove(i);
          bump(s);
          return s;
        }
      }
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    });
  }

  @GetMapping("/public/availability")
  public Map<String, Object> available(
    @RequestParam String productId,
    @RequestParam Instant start,
    @RequestParam int quantity,
    @RequestParam int extraDays
  ) {
    validatePeriod(start, quantity, extraDays);
    ObjectNode s = store.read();
    return Map.of(
      "available",
      available(s, product(s, productId), start, quantity, extraDays)
    );
  }

  @PostMapping("/public/bookings")
  @ResponseStatus(HttpStatus.CREATED)
  public JsonNode book(@Valid @RequestBody BookingInput input) {
    validatePeriod(input.start(), input.quantity(), input.extraDays());
    return store.change(s -> {
      for (JsonNode prior : s.withArray("bookings")) {
        if (
          prior
            .path("requestKey")
            .asText()
            .equals(input.requestKey().toString())
        ) {
          if (
            !prior.path("input").equals(mapper.valueToTree(input))
          ) throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Sama päringuvõti sisaldab teisi andmeid."
          );
          return prior.path("confirmation");
        }
      }
      JsonNode p = product(s, input.productId());
      if (
        !available(s, p, input.start(), input.quantity(), input.extraDays())
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Valitud ajal ei ole piisavalt vabu tooteid."
      );
      BigDecimal base = p
        .path("price")
        .decimalValue()
        .multiply(BigDecimal.valueOf(input.quantity()));
      BigDecimal total = base
        .multiply(
          BigDecimal.ONE.add(
            new BigDecimal("0.10").multiply(
              BigDecimal.valueOf(input.extraDays())
            )
          )
        )
        .setScale(2, RoundingMode.HALF_UP);
      ObjectNode confirmation = mapper
        .createObjectNode()
        .put(
          "reference",
          "EHE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
        )
        .put("start", input.start().toString())
        .put(
          "customerEnd",
          input
            .start()
            .plusSeconds((36L + 24L * input.extraDays()) * 3600)
            .toString()
        )
        .put("total", total);
      ObjectNode booking = s
        .withArray("bookings")
        .addObject()
        .put("requestKey", input.requestKey().toString());
      booking.set("input", mapper.valueToTree(input));
      booking.set("confirmation", confirmation);
      bump(s);
      return confirmation;
    });
  }

  static void validatePeriod(Instant start, int quantity, int extraDays) {
    if (
      start == null ||
      !start.isAfter(Instant.now()) ||
      start.isAfter(Instant.now().plusSeconds(732L * 86400)) ||
      quantity < 1 ||
      quantity > 1000 ||
      extraDays < 0 ||
      extraDays > 30
    ) bad();
    LocalTime local = start.atZone(ZoneId.of("Europe/Tallinn")).toLocalTime();
    if (
      local.isBefore(LocalTime.of(8, 0)) || local.isAfter(LocalTime.of(18, 0))
    ) bad();
  }

  static boolean available(
    ObjectNode s,
    JsonNode p,
    Instant start,
    int quantity,
    int extraDays
  ) {
    Instant end = start.plusSeconds((48L + 24L * extraDays) * 3600);
    int used = 0;
    ZoneId zone = ZoneId.of("Europe/Tallinn");
    for (JsonNode c : s.withArray("closures")) {
      if (
        !c.path("kind").asText().equals("rental") ||
        (!c.path("productId").asText().equals("all") &&
          !c.path("productId").asText().equals(p.path("id").asText()))
      ) continue;
      Instant cs = LocalDate.parse(c.path("from").asText())
          .atStartOfDay(zone)
          .toInstant(),
        ce = LocalDate.parse(c.path("to").asText())
          .plusDays(1)
          .atStartOfDay(zone)
          .toInstant();
      if (start.isBefore(ce) && end.isAfter(cs)) return false;
    }
    for (JsonNode booking : s.withArray("bookings")) {
      JsonNode b = booking.path("input");
      if (!b.path("productId").asText().equals(p.path("id").asText())) continue;
      Instant bs = Instant.parse(b.path("start").asText()),
        be = bs.plusSeconds((48L + 24L * b.path("extraDays").asInt()) * 3600);
      if (start.isBefore(be) && end.isAfter(bs)) used += b
        .path("quantity")
        .asInt();
    }
    return used + quantity <= p.path("stock").asInt();
  }

  static JsonNode product(ObjectNode s, String id) {
    for (JsonNode p : s.withArray("products"))
      if (p.path("id").asText().equals(id)) return p;
    throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "Toodet ei leitud."
    );
  }

  static void checkRevision(ObjectNode s, JsonNode body) {
    if (
      !body.path("revision").isIntegralNumber() ||
      s.path("revision").asLong() != body.path("revision").asLong()
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Andmed muutusid. Värskenda vaadet."
    );
  }

  static void bump(ObjectNode s) {
    s.put("revision", s.path("revision").asLong() + 1);
  }

  static void bad() {
    throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Kontrolli sisestatud andmeid."
    );
  }

  static void bounded(JsonNode n, int max, boolean required) {
    if (
      !n.isTextual() ||
      n.asText().length() > max ||
      (required && n.asText().isBlank())
    ) bad();
  }

  static void validateSettings(JsonNode settings) {
    bounded(settings.path("headline").path("et"), 300, true);
    bounded(settings.path("headline").path("en"), 300, true);
    bounded(settings.path("leader").path("name"), 100, true);
    bounded(settings.path("leader").path("et"), 10000, false);
    bounded(settings.path("leader").path("en"), 10000, false);
    JsonNode pricing = settings.path("pricing");
    try {
      JsonNode defaults = new ObjectMapper()
        .readTree(SiteController.class.getResourceAsStream("/settings.json"))
        .path("pricing");
      shape(pricing, defaults);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    for (String key : List.of("partyPoints", "decorationPoints")) {
      int previous = 0;
      for (JsonNode pair : pricing.path(key)) {
        if (
          !pair.get(0).isIntegralNumber() ||
          pair.get(0).asInt() <= previous ||
          pair.get(0).asInt() > 1000
        ) bad();
        previous = pair.get(0).asInt();
      }
    }
    if (
      !pricing.path("assistantFrom").isIntegralNumber() ||
      pricing.path("assistantFrom").asInt() < 1
    ) bad();
  }

  static void shape(JsonNode value, JsonNode template) {
    if (template.isNumber()) {
      if (
        !value.isNumber() || value.asDouble() < 0 || value.asDouble() > 1000000
      ) bad();
      return;
    }
    if (template.isArray()) {
      if (!value.isArray() || value.size() != template.size()) bad();
      for (int i = 0; i < template.size(); i++) shape(
        value.get(i),
        template.get(i)
      );
      return;
    }
    if (!value.isObject() || value.size() != template.size()) bad();
    template
      .fields()
      .forEachRemaining(e -> shape(value.path(e.getKey()), e.getValue()));
  }
}
