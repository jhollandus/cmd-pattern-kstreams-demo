package com.github.jhollandus.demo.cmd.avro;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public class SpecificAvroDeserializer<T extends SpecificRecord> implements Deserializer<T> {
    public static Map<String, Object> propsForMultiSchemaTopics(String schemaRegistryUrl) {

        Map<String, Object> moreProps = new HashMap<>();
        moreProps.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        //Topic record name strategy allows multiple schemas per topic versus only value and key schemas
        moreProps.put(KafkaAvroDeserializerConfig.KEY_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getCanonicalName());
        moreProps.put(KafkaAvroDeserializerConfig.VALUE_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getCanonicalName());

        return moreProps;
    }


    private final KafkaAvroDeserializer delegate;

    public SpecificAvroDeserializer() {
        this.delegate = new KafkaAvroDeserializer();
    }

    public SpecificAvroDeserializer(SchemaRegistryClient client) {
        this.delegate = new KafkaAvroDeserializer(client);
    }

    public SpecificAvroDeserializer(SchemaRegistryClient client, Map<String, ?> props) {
       this.delegate = new KafkaAvroDeserializer(client, modProps(props));
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        delegate.configure(modProps(configs), isKey);
    }

    @Override
    public T deserialize(String s, byte[] bytes) {
        return (T) delegate.deserialize(s, bytes);
    }

    public T deserialize(String s, byte[] bytes, Schema readerSchema) {
        return (T) delegate.deserialize(s, bytes, readerSchema);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private Map<String, ?> modProps(Map<String, ?> origProps) {
        Map<String, Object> moreProps = new HashMap<>(origProps);
        moreProps.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true");
        return moreProps;
    }
}
