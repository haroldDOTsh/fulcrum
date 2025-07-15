package sh.harold.fulcrum.api.data.impl;

public interface PlayerDataSchema<T> {
    /**
     * Deserializes data from a ResultSet into the schema's object type.
     * @param rs The ResultSet containing the data.
     * @return The deserialized object.
     */
    T deserialize(java.sql.ResultSet rs);
    String schemaKey();

    Class<T> type();
}
