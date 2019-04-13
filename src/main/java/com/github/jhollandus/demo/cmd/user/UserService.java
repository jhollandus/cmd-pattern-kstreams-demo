package com.github.jhollandus.demo.cmd.user;

import com.github.jhollandus.demo.cmd.*;
import com.github.jhollandus.demo.cmd.avro.AvroUtils;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroDeserializer;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroSerde;
import com.github.jhollandus.demo.cmd.avro.SpecificAvroSerializer;
import com.github.jhollandus.demo.cmd.db.UserDbRepo;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.StateRestoreListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;

/**
 * User service contains a kafka streams topology which does the processing of all user commands.
 * <p>
 * The command stream is the system of truth for all users in the system.
 *
 * It generates a CDC stream of user data that is also used to update a relational database.
 */
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final SchemaRegistryClient schemaRegistryClient;
    private final CmdDemoApplication.Config config;
    private final UserDbRepo dbRepo;
    private final Map<String, Object> multiAvroProps;
    private final Map<String, Object> singleAvroProps;

    private KafkaStreams kafkaStreams;

    public UserService(SchemaRegistryClient schemaRegistryClient, CmdDemoApplication.Config config, UserDbRepo dbRepo) {
        this.schemaRegistryClient = schemaRegistryClient;
        this.config = config;
        this.dbRepo = dbRepo;

        multiAvroProps =  SpecificAvroSerializer.propsForMultiSchemaTopics(config.getSchemaRegistryUrl());
        singleAvroProps =  new HashMap<>();
        singleAvroProps.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, config.getSchemaRegistryUrl());
    }

    @PreDestroy
    private void preDestroy() {
        if (kafkaStreams != null) {
            log.info("Closing user service stream.");
            kafkaStreams.close();
            log.info("User service stream closed.");
        }
    }

    @PostConstruct
    private void postConstruct() {
        Topology topology = createTopology();
        kafkaStreams = createStream(topology);

        log.info("Starting user service stream.");
        kafkaStreams.start();
    }

    protected Properties streamProps() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.getAppName());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueExceptionHandler.class.getName());
        props.putAll(SpecificAvroSerializer.propsForMultiSchemaTopics(config.getSchemaRegistryUrl()));
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class.getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class.getName());
        return props;
    }

    protected Topology createTopology() {

        Serde<User> userSerdes = new SpecificAvroSerde<>(schemaRegistryClient, singleAvroProps);

        StreamsBuilder streamsBuilder = new StreamsBuilder();
        KStream<String, SpecificRecord> userCmdStream = streamsBuilder.stream(config.getCmdTopic(), Consumed
                .with(Topology.AutoOffsetReset.LATEST));

        KTable<String, User> userTable = streamsBuilder.table(config.getUserTopic(), Consumed
                .with(Serdes.String(), userSerdes)
                .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST));

        userTable.toStream()
                 .foreach((k, v) -> {
                     try {
                         if (v == null) {
                             dbRepo.deleteById(k);
                         } else {
                             dbRepo.save(UserEntity.fromAvroUser(v));
                         }
                     } catch(Exception e) {
                         log.error("Error while persisting changes to database.", e);
                     }
                });
        //join the user cmd stream with the user table for validation, optimistic lock check, etc
        //should also verify requestBy principal has permission to do this
        KStream<String, ValidatedUserCmd<SpecificRecord>>[] lockBranch = userCmdStream
                .filter((k, v) -> !AvroUtils.schemasMatch(v, UserCmdError.SCHEMA$))
                .peek((k, v) -> log.info("Command received: {}", v))
                .leftJoin(userTable, this::performPreCheck)
                .branch((k, v) -> v.hasError(), (k, v) -> !v.hasError());


        KStream<String, ValidatedUserCmd<SpecificRecord>> lockSucceedStream = lockBranch[1];

        //failure, create cmd error
        KStream<String, ValidatedUserCmd<SpecificRecord>> lockFailStream = lockBranch[0];
        lockFailStream.mapValues(invalid -> cmdError(
                invalid,
                invalid.joinedUser,
                CmdUtils.CMD_INFO_EXTRACTOR.extractFieldValue(invalid.cmd).orElse(null)))
                .to(config.getCmdTopic());

        //Success, dispatch cmd handling
        KStream<String, ValidatedUserCmd<SpecificRecord>>[] cmdBranch = lockSucceedStream.branch(
                (userId, cmdUser) -> AvroUtils.schemasMatch(cmdUser.cmd, UserCreateCmd.SCHEMA$),
                (userId, cmdUser) -> AvroUtils.schemasMatch(cmdUser.cmd, UserDeleteCmd.SCHEMA$),
                (userId, cmdUser) -> AvroUtils.schemasMatch(cmdUser.cmd, UserUpdateCmd.SCHEMA$),
                (userId, cmdUser) -> true);

        handleCreate(cmdBranch[0]);
        handleDelete(cmdBranch[1]);
        handleUpdate(cmdBranch[2]);
        handleCdc(cmdBranch[3]);

        return streamsBuilder.build(streamProps());
    }

    protected KafkaStreams createStream(Topology topology) {

        KafkaStreams stream = new KafkaStreams(topology, streamProps());

        //add logging to these listerners/handlers.  These can also be used to determine the health of this service which
        //can be used in infrastructure monitoring or docker environments.
        stream.setUncaughtExceptionHandler((thread, ex) ->
                log.error("KafkaStreams thread {} died suddenly.", thread.getName(), ex));
        stream.setStateListener(((newState, oldState) ->
                log.info("Stream state changed (old -> new). {} -> {}. Running: {}.",
                        oldState.name(), newState.name(), newState.isRunning())));
        stream.setGlobalStateRestoreListener(new StateRestoreListener() {
            @Override
            public void onRestoreStart(TopicPartition topicPartition, String storeName, long startingOffset, long endingOffset) {
                log.info("Beginning restoration of global state for topic-partition {}-{}. Offset range {} - {}.",
                        topicPartition.topic(), topicPartition.partition(), startingOffset, endingOffset);
            }

            @Override
            public void onBatchRestored(TopicPartition topicPartition, String storeName, long batchEndOffset, long numRestored) {
                log.info("batch restored for global state on topic-partition {}-{}. End offset: {}.",
                        topicPartition.topic(), topicPartition.partition(), batchEndOffset);

            }

            @Override
            public void onRestoreEnd(TopicPartition topicPartition, String storeName, long totalRestored) {
                log.info("Finished restoration for global state on topic-partition {}-{}. Total restored: {}.",
                        topicPartition.topic(), topicPartition.partition(), totalRestored);
            }
        });

        return stream;
    }

    /**
     * Execute business logic around user creation.
     */
    private void handleCreate(KStream<String, ValidatedUserCmd<SpecificRecord>> cmdBranch) {

        KStream<String, ValidatedUserCmd<UserCreateCmd>>[] validBranch = cmdBranch.mapValues(jcs -> {
            log.info("Creating user");
            UserCreateCmd createCmd = (UserCreateCmd) jcs.cmd;

            List<String> errs = validateUser(createCmd.getUser());
            return new ValidatedUserCmd<>(createCmd, jcs.joinedUser)
                    .addAllError(errs);
        }).branch(
                (userId, validated) -> !validated.hasError(),
                (userId, validated) -> validated.hasError()
        );

        //valid
        validBranch[0]
                .mapValues(valid -> UserCreated.newBuilder()
                        .setCmdInfo(valid.cmd.getCmdInfo())
                        .setUserBuilder(User.newBuilder(valid.cmd.getUser())
                                .setVersion(valid.cmd.getUser().getVersion() + 1))
                        .build())
                .to(config.getCmdTopic());

        //invalid
        validBranch[1]
                .mapValues(invalid -> cmdError(invalid, invalid.cmd.getUser(), invalid.cmd.getCmdInfo()))
                .to(config.getCmdTopic());
    }

    /**
     * Execute business logic for user delete.
     */
    private void handleDelete(KStream<String, ValidatedUserCmd<SpecificRecord>> cmdBranch) {
        cmdBranch.mapValues(jcs -> (UserDeleteCmd) jcs.cmd)
                .peek((k, v) -> log.info("Deleting user"))
                .mapValues((userId, delCmd) -> UserDeleted.newBuilder()
                        .setCmdInfo(delCmd.getCmdInfo())
                        .setUser(delCmd.getUser())
                        .build())
                .to(config.getCmdTopic());

    }

    /**
     * Execute business logic for user update.
     */
    private void handleUpdate(KStream<String, ValidatedUserCmd<SpecificRecord>> cmdBranch) {
        KStream<String, ValidatedUserCmd<UserUpdateCmd>>[] validBranch = cmdBranch.mapValues(jcs -> {
            UserUpdateCmd createCmd = (UserUpdateCmd) jcs.cmd;
            log.info("Updating user");
            List<String> errs = validateUser(createCmd.getUser());
            return new ValidatedUserCmd<>(createCmd, jcs.joinedUser)
                    .addAllError(errs);
        }).branch(
                (userId, validated) -> !validated.hasError(),
                (userId, validated) -> validated.hasError()
        );

        //valid
        validBranch[0]
                .mapValues(valid -> UserUpdated.newBuilder()
                        .setCmdInfo(valid.cmd.getCmdInfo())
                        .setUserBuilder(User.newBuilder(valid.cmd.getUser())
                                .setVersion(valid.cmd.getUser().getVersion() + 1))
                        .build())
                .to(config.getCmdTopic());

        //invalid
        validBranch[1]
                .mapValues(invalid -> cmdError(invalid, invalid.cmd.getUser(), invalid.cmd.getCmdInfo()))
                .to(config.getCmdTopic());
    }

    private UserCmdError cmdError(ValidatedUserCmd<?> invalid, User invalidUser, CmdInfo cmdInfo) {
        return UserCmdError.newBuilder()
                .setUser(invalidUser)
                .setCmdErrorBuilder(CmdError.newBuilder()
                        .setProcess(config.getAppName())
                        .setError(String.join(System.lineSeparator(), invalid.errorMessages))
                        .setTimestamp(System.currentTimeMillis())
                        .setCmdInfo(cmdInfo))
                .build();
    }

    private void handleCdc(KStream<String, ValidatedUserCmd<SpecificRecord>> cmdBranch) {
        cmdBranch.filter((k, v) ->
                    AvroUtils.schemasMatch(v.cmd, UserUpdated.SCHEMA$)
                    || AvroUtils.schemasMatch(v.cmd, UserDeleted.SCHEMA$)
                    || AvroUtils.schemasMatch(v.cmd, UserCreated.SCHEMA$))
                 .flatMapValues(v -> {
                     //single null val for delete
                     if(AvroUtils.schemasMatch(v.cmd, UserDeleted.SCHEMA$)) {
                         return Collections.singletonList(null);
                     }

                     //single user val for upsert
                     Optional<User> userOpt = CmdUtils.USER_EXTRACTOR.extractFieldValue(v.cmd);
                     if(userOpt.isPresent()) {
                         return Collections.singleton(userOpt.get());
                     }

                     //no user than skip
                     return Collections.emptyList();
                 })
                .to(config.getUserTopic(), Produced.with(
                        Serdes.String(),
                        new SpecificAvroSerde<>(schemaRegistryClient, singleAvroProps)));

    }

    private List<String> validateUser(User user) {
        List<String> errors = new LinkedList<>();
        //validation
        if (user == null) {
            errors.add("No user present in command.");
            return errors;
        }

        if ("".equals(user.getName())) {
            errors.add("Name is required");
        }

        if ("".equals(user.getEmail())) {
            errors.add("Email is required");
        }

        return errors;
    }

    /**
     * Performs validation of all commands before processing is even begun. Some of these may include:
     * - Permission checks
     * - Optimistic lock check
     */
    private ValidatedUserCmd<SpecificRecord> performPreCheck(SpecificRecord cmd, User joinedUser) {
        final ValidatedUserCmd<SpecificRecord> validatedCmd = new ValidatedUserCmd<>(cmd, joinedUser);

        //if user is on the command then verify the version otherwise pass it.
        return CmdUtils.USER_EXTRACTOR.extractFieldValue(cmd)
                .map(user -> {
                    if (joinedUser != null && !(user.getVersion() >= joinedUser.getVersion())) {
                        validatedCmd.addError("Optimistic Lock Error, target version less than current version");
                    }
                    return validatedCmd;
                })
                .orElse(validatedCmd);
    }

    public static class ValidatedUserCmd<T> {
        final List<String> errorMessages = new LinkedList<>();
        final User joinedUser;
        final T cmd;

        public ValidatedUserCmd(T cmd, User joinedUser) {
            this.cmd = cmd;
            this.joinedUser = joinedUser;
        }

        public boolean hasError() {
            return !errorMessages.isEmpty();
        }

        public ValidatedUserCmd<T> addError(String errMsg) {
            errorMessages.add(errMsg);
            return this;
        }

        public ValidatedUserCmd<T> addAllError(List<String> errorMessages) {
            this.errorMessages.addAll(errorMessages);
            return this;
        }
    }
}
