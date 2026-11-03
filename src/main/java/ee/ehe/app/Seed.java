package ee.ehe.app;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;

@Component
class Seed implements ApplicationRunner {

  private final StateStore store;
  private final ObjectMapper mapper;

  Seed(StateStore store, ObjectMapper mapper) {
    this.store = store;
    this.mapper = mapper;
  }

  public void run(ApplicationArguments args) throws Exception {
    ObjectNode initial = mapper.createObjectNode().put("revision", 0);
    initial.set(
      "settings",
      mapper.readTree(getClass().getResourceAsStream("/settings.json"))
    );
    initial.set(
      "products",
      mapper
        .readTree(getClass().getResourceAsStream("/catalog.json"))
        .path("products")
    );
    initial.putArray("bookings");
    initial.putArray("closures");
    store.initialize(initial);
  }
}
