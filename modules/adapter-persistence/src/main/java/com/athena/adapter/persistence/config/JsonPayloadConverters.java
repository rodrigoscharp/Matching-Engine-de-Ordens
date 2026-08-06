package com.athena.adapter.persistence.config;

import com.athena.adapter.persistence.entity.JsonPayload;
import java.sql.SQLException;
import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * Binds {@link JsonPayload} to PostgreSQL's {@code jsonb} type.
 *
 * <p>The JDBC driver will not implicitly widen a {@code varchar} parameter into {@code jsonb} —
 * it fails with "column is of type jsonb but expression is of type character varying" — so the
 * value has to be handed over as a typed {@link PGobject}.
 */
public final class JsonPayloadConverters {

  private JsonPayloadConverters() {}

  @WritingConverter
  public enum ToPGobject implements Converter<JsonPayload, PGobject> {
    INSTANCE;

    @Override
    public PGobject convert(JsonPayload source) {
      var pg = new PGobject();
      pg.setType("jsonb");
      try {
        pg.setValue(source.json());
      } catch (SQLException e) {
        throw new IllegalStateException("Failed to bind JSON payload", e);
      }
      return pg;
    }
  }

  @ReadingConverter
  public enum FromPGobject implements Converter<PGobject, JsonPayload> {
    INSTANCE;

    @Override
    public JsonPayload convert(PGobject source) {
      return JsonPayload.of(source.getValue());
    }
  }
}
