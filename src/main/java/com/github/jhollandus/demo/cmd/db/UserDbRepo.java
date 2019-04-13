package com.github.jhollandus.demo.cmd.db;

import com.github.jhollandus.demo.cmd.User;
import com.github.jhollandus.demo.cmd.user.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserDbRepo  extends JpaRepository<UserEntity, String> { }
