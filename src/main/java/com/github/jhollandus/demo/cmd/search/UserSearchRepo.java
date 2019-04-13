package com.github.jhollandus.demo.cmd.search;

import com.github.jhollandus.demo.cmd.user.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface UserSearchRepo extends ElasticsearchRepository<UserEntity, String> {
    Page<UserEntity> findByName(String name, Pageable pageable);
}
