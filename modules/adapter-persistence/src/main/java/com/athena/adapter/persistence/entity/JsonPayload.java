package com.athena.adapter.persistence.entity;

/**
 * A JSON document on its way to or from a {@code jsonb} column.
 *
 * <p>Exists so the payload can be told apart from every other {@code String} field on the record:
 * Spring Data JDBC picks converters by Java type, so a plain {@code String} payload would either
 * be bound as {@code varchar} (which {@code jsonb} rejects) or force every string column through a
 * JSON converter.
 */
public record JsonPayload(String json) {

  public static JsonPayload of(String json) {
    return new JsonPayload(json);
  }

  @Override
  public String toString() {
    return json;
  }
}
