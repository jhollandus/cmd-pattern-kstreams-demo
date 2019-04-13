package com.github.jhollandus.demo.cmd.user;

import com.github.jhollandus.demo.cmd.*;
import com.github.jhollandus.demo.cmd.avro.AvroUtils;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroDeserializer;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroSerializer;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Supports the public synchronous API which doesn't want to return until the results of their commands are either
 * indexed or determined to be in error.
 */
public class UserCmdCoordinator {
    private static final Logger log = LoggerFactory.getLogger(UserCmdCoordinator.class);

    private final SchemaRegistryClient schemaRegistryClient;
    private final CmdDemoApplication.Config config;
    private final ConcurrentHashMap<String, CompletableFuture<User>> waiters = new ConcurrentHashMap<>();

    private Producer<String, SpecificRecord> cmdProducer;
    private Consumer<String, SpecificRecord> broadcastConsumer;
    private BroadcastListener broadcastListener;

    public UserCmdCoordinator(SchemaRegistryClient schemaRegistryClient, CmdDemoApplication.Config config) {
        this.schemaRegistryClient = schemaRegistryClient;
        this.config = config;
    }

    public User createUser(String requester, User newUser) {
        UserCreateCmd cmd = UserCreateCmd.newBuilder()
                .setCmdInfoBuilder(CmdInfo.newBuilder()
                    .setCmdSagaId(UUID.randomUUID().toString())
                    .setRequestedBy(requester)
                    .setRequestTime(System.currentTimeMillis()))
                .setUser(User.newBuilder(newUser)
                    .setId(UUID.randomUUID().toString())
                    .build())
                .build();

        return publishAndParkpark(cmd.getCmdInfo().getCmdSagaId(), cmd.getUser().getId(), cmd);
    }

    public User deleteUser(String requester, User newUser) {
        UserDeleteCmd cmd = UserDeleteCmd.newBuilder()
                .setCmdInfoBuilder(CmdInfo.newBuilder()
                        .setCmdSagaId(UUID.randomUUID().toString())
                        .setRequestedBy(requester)
                        .setRequestTime(System.currentTimeMillis()))
                .setUserBuilder(User.newBuilder(newUser))
                .build();

        return publishAndParkpark(cmd.getCmdInfo().getCmdSagaId(), cmd.getUser().getId(), cmd);
    }

    public User updateUser(String requester, User newUser) {
        UserUpdateCmd cmd = UserUpdateCmd.newBuilder()
                .setCmdInfoBuilder(CmdInfo.newBuilder()
                        .setCmdSagaId(UUID.randomUUID().toString())
                        .setRequestedBy(requester)
                        .setRequestTime(System.currentTimeMillis()))
                .setUserBuilder(User.newBuilder(newUser))
                .build();

        return publishAndParkpark(cmd.getCmdInfo().getCmdSagaId(), cmd.getUser().getId(), cmd);
    }

    @PreDestroy
    private void preDestroy() {
        if(broadcastListener != null) {
            broadcastListener.stop();
        }

        if(broadcastConsumer != null) {
            broadcastConsumer.close();
        }

        if(cmdProducer != null) {
            cmdProducer.close();
        }
    }

    @PostConstruct
    private void postConstruct() {

        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");

        this.cmdProducer = new KafkaProducer<>(
                producerProps,
                new StringSerializer(),
                new SpecificAvroSerializer<>(
                        schemaRegistryClient,
                        SpecificAvroSerializer.propsForMultiSchemaTopics(config.getSchemaRegistryUrl())));


        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        broadcastConsumer = new KafkaConsumer<>(
                consumerProps,
                new StringDeserializer(),
                new SpecificAvroDeserializer<>(
                        schemaRegistryClient,
                        SpecificAvroDeserializer.propsForMultiSchemaTopics(config.getSchemaRegistryUrl())));

        broadcastListener = new BroadcastListener(broadcastConsumer, config.getCmdTopic());
        broadcastListener.startListening(this::handleBroadcast);
    }

    private User publishAndParkpark(String cmdSagaId, String userId, SpecificRecord cmd) {
        CompletableFuture<User> processFuture = new CompletableFuture<>();
        waiters.put(cmdSagaId, processFuture);

        CompletableFuture<Void> produceFuture = CompletableFuture.runAsync(() ->{
            try {
                cmdProducer.send(new ProducerRecord<>(config.getCmdTopic(), userId, cmd)).get();
            } catch(Exception e) {
                //better error handling needed
                throw new RuntimeException(e);
            }
        });

       try {
            return produceFuture.thenCompose(p -> processFuture).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
           if(e.getCause() instanceof CmdException) {
               throw (CmdException) e.getCause();
           }
           throw new RuntimeException(e);
        } catch(Exception e) {
           throw new RuntimeException(e);
       }
    }

    private void handleBroadcast(ConsumerRecord<String, SpecificRecord> record) {
        SpecificRecord value = record.value();

        if (AvroUtils.schemasMatch(value, UserCmdError.SCHEMA$)) {
            UserCmdError cmdError = (UserCmdError) value;

            //an error, notify the waiter if any
            if (cmdError.getCmdError() != null && cmdError.getCmdError().getCmdInfo() != null) {
                String cmdSagaId = cmdError.getCmdError().getCmdInfo().getCmdSagaId();
                CompletableFuture<User> future = waiters.remove(cmdSagaId);
                if(future != null) {
                    future.completeExceptionally(new CmdException(cmdError.getCmdError()));
                }
            }
        } else if (AvroUtils.schemasMatch(value, UserIndexed.SCHEMA$)) {
            UserIndexed indexed = (UserIndexed) value;

            //it's been indexed, notify any waiter
            if (indexed.getCmdInfo() != null) {
                CompletableFuture<User> future = waiters.remove(indexed.getCmdInfo().getCmdSagaId());
                if(future != null) {
                    future.complete(indexed.getUser());
                }
            }
        }
    }

    static class BroadcastListener {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final Consumer<String, SpecificRecord> consumer;
        private final String topic;
        private Thread thread;
        private BiConsumer<Thread, Throwable> errorHandler = (th, ex) -> {
        };

        public BroadcastListener(Consumer<String, SpecificRecord> consumer, String topic) {
            this.consumer = consumer;
            this.topic = topic;
        }

        public void startListening(java.util.function.Consumer<ConsumerRecord<String, SpecificRecord>> recordHandler) {
            running.set(true);
            thread = new Thread(() -> start(recordHandler));
            thread.setName("broadcast-listener");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(this::onError);
            thread.start();
        }

        public BroadcastListener setErrorHandler(BiConsumer<Thread, Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        private void onError(Thread t, Throwable e) {
            running.set(false);
            log.error("Unexpected thread death for thead '{}'", t.getName(), e);
            this.errorHandler.accept(t, e);
        }

        public boolean isRunning() {
            return running.get();
        }

        public void stop() {
            running.set(false);
        }

        private void start(java.util.function.Consumer<ConsumerRecord<String, SpecificRecord>> recordHandler) {
            List<TopicPartition> tpList = consumer.partitionsFor(topic)
                    .stream()
                    .map(pInfo -> new TopicPartition(pInfo.topic(), pInfo.partition()))
                    .collect(Collectors.toList());

            consumer.assign(tpList);
            consumer.seekToEnd(tpList);

            try {
                while (running.get()) {
                    ConsumerRecords<String, SpecificRecord> records = consumer.poll(Duration.of(10, ChronoUnit.MILLIS));
                    for(ConsumerRecord<String, SpecificRecord> rec: records) {
                        log.info("Record of type {} received: {}",
                                rec.value().getSchema() != null ? rec.value().getSchema().getName() : "null",
                                rec);
                        recordHandler.accept(rec);
                    }
                }
            } finally {
                log.info("Closing broadcast listener consumer");
                consumer.close();
            }
        }
    }
}
