package com.github.jhollandus.demo.cmd.service;

import com.github.jhollandus.demo.cmd.*;
import com.github.jhollandus.demo.cmd.avro.AvroUtils;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroDeserializer;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroSerializer;
import com.github.jhollandus.demo.cmd.search.UserSearchRepo;
import com.github.jhollandus.demo.cmd.user.UserEntity;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.KafkaThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Indexes all cmd results into our search service. Much like a stream processor except it reaches out to an
 * external service to perform the update which is more like a connector.
 */
public class IndexService {
    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final KafkaThread thread = new KafkaThread("index-service", this::run, true);
    private final UserSearchRepo searchRepo;
    private final CmdDemoApplication.Config config;
    private final SchemaRegistryClient schemaRegistryClient;

    private Consumer<String, SpecificRecord> consumer;
    private Producer<String, SpecificRecord> producer;

    public IndexService(UserSearchRepo searchRepo, CmdDemoApplication.Config config, SchemaRegistryClient schemaRegistryClient) {
        this.searchRepo = searchRepo;
        this.config = config;
        this.schemaRegistryClient = schemaRegistryClient;
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            log.info("Stopping index service.");
            thread.join();
            log.info("Stopped index service.");
        } catch (InterruptedException e) {
            log.error("Failed to stop index service.", e);
        }
    }

    @PostConstruct
    public void start() {
        thread.start();
    }

    private void run() {
        running.set(true);

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        producerProps.put(ProducerConfig.CLIENT_ID_CONFIG, config.getAppName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
        producer = new KafkaProducer<>(
                producerProps,
                new StringSerializer(),
                new SpecificAvroSerializer<>(
                        schemaRegistryClient,
                        SpecificAvroSerializer.propsForMultiSchemaTopics(config.getSchemaRegistryUrl())));

        Properties props = new Properties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getAppName() + "-indexer");
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, config.getAppName());

        consumer = new KafkaConsumer<>(
                props,
                new StringDeserializer(),
                new SpecificAvroDeserializer<>(
                        schemaRegistryClient,
                        SpecificAvroDeserializer.propsForMultiSchemaTopics(config.getSchemaRegistryUrl())));

        consumer.subscribe(Collections.singleton(config.getCmdTopic()));
        while (running.get()) {
            consumer.poll(Duration.of(10, ChronoUnit.MILLIS)).forEach(this::indexRecord);
        }

        //asked to stop
        log.info("Closing index service consumer");
        consumer.close();

        log.info("Closing index service producer");
        producer.close();
    }

    private void indexRecord(ConsumerRecord<String, SpecificRecord> record) {
        try {
            CmdInfo cmdInfo;
            User user;
            if (AvroUtils.schemasMatch(record.value(), UserUpdated.SCHEMA$)) {
                UserUpdated updated = (UserUpdated) record.value();
                searchRepo.save(UserEntity.fromAvroUser(updated.getUser()));
                cmdInfo = updated.getCmdInfo();
                user = updated.getUser();

            } else if (AvroUtils.schemasMatch(record.value(), UserCreated.SCHEMA$)) {
                UserCreated created = (UserCreated) record.value();
                searchRepo.save(UserEntity.fromAvroUser(created.getUser()));
                cmdInfo = created.getCmdInfo();
                user = created.getUser();

            } else if (AvroUtils.schemasMatch(record.value(), UserDeleted.SCHEMA$)) {
                UserDeleted deleted = (UserDeleted) record.value();
                searchRepo.delete(UserEntity.fromAvroUser(deleted.getUser()));
                cmdInfo = deleted.getCmdInfo();
                user = deleted.getUser();

            } else {
                //skip
                return;
            }

            log.info("Search index has been updated with {} command result. Commmand Result: ",
                    record.value().getSchema() != null ? record.value().getSchema().getName() : "null",
                    record.value());
            producer.send(new ProducerRecord<>(config.getCmdTopic(), record.key(), UserIndexed.newBuilder()
                    .setCmdInfo(cmdInfo)
                    .setUser(user)
                    .build()));
        }  catch(Exception ex) {
            log.error("Failed to update search index for record: {}. Moving on.", record, ex);
        }

    }
}
