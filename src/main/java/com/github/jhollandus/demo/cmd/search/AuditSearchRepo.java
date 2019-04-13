package com.github.jhollandus.demo.cmd.search;

import com.github.jhollandus.demo.cmd.UserCmdAudit;
import com.github.jhollandus.demo.cmd.service.UserAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface AuditSearchRepo extends ElasticsearchRepository<UserAuditEntity, String> {
    Page<UserAuditEntity> findByRequestedBy(String requestedBy, Pageable pageable);

    Page<UserAuditEntity> findByCmdName(String cmdName, Pageable pageable);

    Page<UserAuditEntity> findByUserName(String userName, Pageable pageable);
}
