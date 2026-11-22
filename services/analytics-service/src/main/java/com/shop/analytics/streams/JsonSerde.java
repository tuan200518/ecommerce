package com.shop.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/** Serde JSON đơn giản cho state store của Kafka Streams. */
public class JsonSerde<T> implements Serde<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;

    public JsonSerde(ObjectMapper mapper, Class<T> type) {
        this.mapper = mapper;
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            if (data == null) {
                return null;
            }
            try {
                return mapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new SerializationException("Không serialize được " + type.getSimpleName(), e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return mapper.readValue(bytes, type);
            } catch (Exception e) {
                throw new SerializationException("Không deserialize được " + type.getSimpleName(), e);
            }
        };
    }
}
