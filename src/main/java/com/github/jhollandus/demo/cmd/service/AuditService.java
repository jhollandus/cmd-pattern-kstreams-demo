package com.github.jhollandus.demo.cmd.service;

import com.github.jhollandus.demo.cmd.CmdDemoApplication;
import com.github.jhollandus.demo.cmd.CmdUtils;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroDeserializer;
import com.github.jhollandus.demo.cmd.search.AuditSearchRepo;
import com.github.jhollandus.demo.cmd.user.UserEntity;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final KafkaThread thread = new KafkaThread("audit-service", this::run, true);
    private final AuditSearchRepo searchRepo;
    private final CmdDemoApplication.Config config;
    private final SchemaRegistryClient schemaRegistryClient;

    private Consumer<String, SpecificRecord> consumer;

    public AuditService(AuditSearchRepo searchRepo, CmdDemoApplication.Config config, SchemaRegistryClient schemaRegistryClient) {
        this.searchRepo = searchRepo;
        this.config = config;
        this.schemaRegistryClient = schemaRegistryClient;
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            log.info("Stopping audit service.");
            thread.join();
            log.info("Stopped audit service.");
        } catch (InterruptedException e) {
            log.error("Failed to stop audit service.", e);
        }
    }

    @PostConstruct
    public void start() {
        thread.start();
    }

    private void run() {
        running.set(true);

        Properties props = new Properties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getAppName() + "-audit");
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, config.getAppName() + "-audit");

        consumer = new KafkaConsumer<>(
                props,
                new StringDeserializer(),
                new SpecificAvroDeserializer<>(
                        schemaRegistryClient,
                        SpecificAvroDeserializer.propsForMultiSchemaTopics(config.getSchemaRegistryUrl())));

        consumer.subscribe(Collections.singleton(config.getCmdTopic()));
        while (running.get()) {
            consumer.poll(Duration.of(10, ChronoUnit.MILLIS)).forEach(this::auditRecord);
        }

        //asked to stop
        log.info("Closing audit service consumer");
        consumer.close();
    }

    private void auditRecord(ConsumerRecord<String, SpecificRecord> record) {
        try {
            UserAuditEntity auditBldr = new UserAuditEntity();
            CmdUtils.CMD_INFO_EXTRACTOR.extractFieldValue(record.value()).ifPresent(cmdInfo -> {
                auditBldr.setRequestedBy(cmdInfo.getRequestedBy());
                auditBldr.setRequestTime(cmdInfo.getRequestTime());
                auditBldr.setCmdSagaId(cmdInfo.getCmdSagaId());
            });
            CmdUtils.USER_EXTRACTOR.extractFieldValue(record.value()).ifPresent(user -> {
                auditBldr.setUser(UserEntity.fromAvroUser(user));
            });
            auditBldr.setAuditTime(System.currentTimeMillis());
            auditBldr.setCmdName(record.value().getSchema().getFullName());
            searchRepo.save(auditBldr);

        }  catch(Exception ex) {
            log.error("Failed to update search audit for record: {}. Moving on.", record, ex);
        }

    }
}
