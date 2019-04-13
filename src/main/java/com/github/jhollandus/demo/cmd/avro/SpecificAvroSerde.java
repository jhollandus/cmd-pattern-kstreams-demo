package com.github.jhollandus.demo.cmd.avro;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

public class SpecificAvroSerde<T extends SpecificRecord> implements Serde<T> {
    private final SpecificAvroSerializer<T> serializer;
    private final SpecificAvroDeserializer<T> deserializer;

    public SpecificAvroSerde() {
        this.serializer = new SpecificAvroSerializer<>();
        this.deserializer = new SpecificAvroDeserializer<>();
    }

    public SpecificAvroSerde(SchemaRegistryClient client, Map<String, ?> configs) {
        this.serializer = new SpecificAvroSerializer<>(client, configs);
        this.deserializer = new SpecificAvroDeserializer<>(client, configs);
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        this.serializer.configure(configs, isKey);
        this.deserializer.configure(configs, isKey);
    }

    @Override
    public void close() {
        this.serializer.close();
        this.deserializer.close();
    }

    @Override
    public Serializer<T> serializer() {
        return serializer;
    }

    @Override
    public Deserializer<T> deserializer() {
        return deserializer;
    }
}
