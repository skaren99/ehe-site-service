package ee.ehe.app;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StateStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public StateStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public ObjectNode read() {
    return parse(
      jdbc.queryForObject(
        "select payload from app_state where id=1",
        String.class
      )
    );
  }

  @Transactional
  public <T> T change(Function<ObjectNode, T> action) {
    ObjectNode state = parse(
      jdbc.queryForObject(
        "select payload from app_state where id=1 for update",
        String.class
      )
    );
    T result = action.apply(state);
    jdbc.update("update app_state set payload=? where id=1", state.toString());
    return result;
  }

  @Transactional
  public void initialize(ObjectNode initial) {
    // ON CONFLICT permits concurrent replica startup on PostgreSQL.
    jdbc.update(
      "insert into app_state(id,payload) values(1,?) on conflict do nothing",
      initial.toString()
    );
  }

  private ObjectNode parse(String value) {
    try {
      return (ObjectNode) mapper.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored state", e);
    }
  }
}
