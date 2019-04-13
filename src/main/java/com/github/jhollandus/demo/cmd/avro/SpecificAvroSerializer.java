package com.github.jhollandus.demo.cmd.avro;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serializer;

import java.util.HashMap;
import java.util.Map;

public class SpecificAvroSerializer<T extends SpecificRecord> implements Serializer<T> {
    public static Map<String, Object> propsForMultiSchemaTopics(String schemaRegistryUrl) {
        return SpecificAvroDeserializer.propsForMultiSchemaTopics(schemaRegistryUrl);
    }

    private final KafkaAvroSerializer delegate;

    public SpecificAvroSerializer() {
        this.delegate = new KafkaAvroSerializer();
    }

    public SpecificAvroSerializer(SchemaRegistryClient client) {
        this.delegate = new KafkaAvroSerializer(client);
    }

    public SpecificAvroSerializer(SchemaRegistryClient client, Map<String, ?> props) {
        this.delegate = new KafkaAvroSerializer(client, props);
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        this.delegate.configure(configs, isKey);
    }

    @Override
    public byte[] serialize(String topic, T data) {
        return this.delegate.serialize(topic, data);
    }

    @Override
    public void close() {
        this.delegate.close();
    }
}
