package com.github.jhollandus.demo.cmd.service;

import com.github.jhollandus.demo.cmd.user.UserEntity;
import org.springframework.data.elasticsearch.annotations.Document;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.validation.constraints.NotNull;

@Document(indexName = "audit-idx", type = "audits")
public class UserAuditEntity {
    private String id;
    private UserEntity user;
    private Long requestTime;
    private String requestedBy;
    private String cmdSagaId;
    private String cmdName;
    private Long auditTime;


    @Id
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCmdSagaId() {
        return cmdSagaId;
    }

    public void setCmdSagaId(String cmdSagaId) {
        this.cmdSagaId = cmdSagaId;
    }

    public UserEntity getUser() {
        return user;
    }

    public void setUser(UserEntity user) {
        this.user = user;
    }

    public Long getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(Long requestTime) {
        this.requestTime = requestTime;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    @NotNull
    public String getCmdName() {
        return cmdName;
    }

    public void setCmdName(String cmdName) {
        this.cmdName = cmdName;
    }

    @NotNull
    public Long getAuditTime() {
        return auditTime;
    }

    public void setAuditTime(Long auditTime) {
        this.auditTime = auditTime;
    }
}
