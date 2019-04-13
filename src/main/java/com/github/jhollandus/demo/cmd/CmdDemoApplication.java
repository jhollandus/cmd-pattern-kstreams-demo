package com.github.jhollandus.demo.cmd;

import com.github.jhollandus.demo.cmd.db.UserDbRepo;
import com.github.jhollandus.demo.cmd.search.AuditSearchRepo;
import com.github.jhollandus.demo.cmd.search.UserSearchRepo;
import com.github.jhollandus.demo.cmd.service.AuditService;
import com.github.jhollandus.demo.cmd.service.IndexService;
import com.github.jhollandus.demo.cmd.user.UserCmdCoordinator;
import com.github.jhollandus.demo.cmd.user.UserController;
import com.github.jhollandus.demo.cmd.user.UserService;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@SpringBootApplication
@EnableJpaRepositories(basePackages = {"com.github.jhollandus.demo.cmd.db"})
@EnableElasticsearchRepositories(basePackages = {"com.github.jhollandus.demo.cmd.search"})
@EnableSwagger2
public class CmdDemoApplication {

    public static void main(String... args) {
        SpringApplication.run(CmdDemoApplication.class, args);
    }

    @Bean
    public SchemaRegistryClient schemaRegistryClient(Config config) {
        return new CachedSchemaRegistryClient(config.schemaRegistryUrl, 1000);
    }

    @Bean
    public UserCmdCoordinator userCmdCoordinator(
            Config config,
            SchemaRegistryClient schemaRegistryClient) {

        return new UserCmdCoordinator(schemaRegistryClient, config);
    }

    @Bean
    public IndexService indexService(
            UserSearchRepo searchRepo,
            Config config,
            SchemaRegistryClient schemaRegistryClient) {

        return new IndexService(searchRepo, config, schemaRegistryClient);
    }


    @Bean
    public AuditService auditService(
            AuditSearchRepo searchRepo,
            Config config,
            SchemaRegistryClient schemaRegistryClient) {

        return new AuditService(searchRepo, config, schemaRegistryClient);
    }

    @Bean
    public UserService userService(
            SchemaRegistryClient schemaRegistryClient,
            Config config,
            UserDbRepo dbRepo) {

       return new UserService(schemaRegistryClient, config, dbRepo);
    }

    @Bean
    public UserController userController(
            UserCmdCoordinator userCmdCoordinator,
            UserSearchRepo userSearchRepo,
            UserDbRepo userDbRepo) {

        return new UserController(userCmdCoordinator, userSearchRepo, userDbRepo);
    }

    @Bean
    @ConfigurationProperties(prefix="demo")
    public Config config() {
        return new Config();
    }

    public static class Config {
        private String appName;
        private String cmdTopic;
        private String userTopic;
        private String bootstrapServers;
        private String schemaRegistryUrl;

        public String getUserTopic() {
            return userTopic;
        }

        public void setUserTopic(String userTopic) {
            this.userTopic = userTopic;
        }

        public String getAppName() {
            return appName;
        }

        public void setAppName(String appName) {
            this.appName = appName;
        }

        public String getCmdTopic() {
            return cmdTopic;
        }

        public void setCmdTopic(String cmdTopic) {
            this.cmdTopic = cmdTopic;
        }

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getSchemaRegistryUrl() {
            return schemaRegistryUrl;
        }

        public void setSchemaRegistryUrl(String schemaRegistryUrl) {
            this.schemaRegistryUrl = schemaRegistryUrl;
        }
    }
}
