package com.github.jhollandus.demo.cmd.user;

import com.github.jhollandus.demo.cmd.User;
import org.springframework.data.elasticsearch.annotations.Document;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Version;
import javax.validation.constraints.NotBlank;

@Document(indexName = "user-idx", type = "users")
@Entity
public class UserEntity {
    public static UserEntity fromAvroUser(User user) {
        UserEntity userEntity = new UserEntity();
        userEntity.setEmail(user.getEmail());
        userEntity.setName(user.getName());
        userEntity.setId(user.getId());
        userEntity.setVersion(user.getVersion().intValue());

        return userEntity;
    }

    private String id;

    private String name;

    private String email;

    private int version;

    @Id
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @NotBlank
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @NotBlank
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Version
    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}

