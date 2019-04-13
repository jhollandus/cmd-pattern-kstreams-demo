package com.github.jhollandus.demo.cmd.user;

import com.github.jhollandus.demo.cmd.CmdException;
import com.github.jhollandus.demo.cmd.User;
import com.github.jhollandus.demo.cmd.db.UserDbRepo;
import com.github.jhollandus.demo.cmd.search.UserSearchRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Optional;

/**
 * Note: skipping real security handling for the sake of brevity and instead having a principal sent in via the
 * "user" header. Do not do this in production.
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserCmdCoordinator userCmdCoordinator;
    private final UserSearchRepo searchRepo;
    private final UserDbRepo dbRepo;

    @Autowired
    public UserController(UserCmdCoordinator userCmdCoordinator, UserSearchRepo searchRepo, UserDbRepo dbRepo) {
        this.userCmdCoordinator = userCmdCoordinator;
        this.searchRepo = searchRepo;
        this.dbRepo = dbRepo;
    }

    @GetMapping("/user")
    public Page<UserEntity> getAllUsers(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {

        return searchRepo.findAll(PageRequest.of(page, size));
    }


    @PostMapping("/user")
    public UserEntity createUser(
            @RequestBody CreateUser user,
            @RequestHeader("user") String principal) {

        User result = userCmdCoordinator.createUser(principal, User.newBuilder()
                .setEmail(user.getEmail())
                .setName(user.getName())
                .build());

        return UserEntity.fromAvroUser(result);
    }


    @GetMapping("/user/{id}")
    public Optional<UserEntity> getUserById(
            @PathVariable(value = "id") String userId,
            @RequestHeader("user") String user) {

        return dbRepo.findById(userId);
    }

    @PutMapping("/user/{id}")
    public UserEntity updateUser(@PathVariable(value = "id") String userId,
                                 @RequestBody UpdateUser user,
                                 @RequestHeader("user") String principal) {

        User result = userCmdCoordinator.updateUser(principal, User.newBuilder()
                .setName(user.getName())
                .setEmail(user.getEmail())
                .setId(userId)
                .setVersion(user.getVersion())
                .build());

        return UserEntity.fromAvroUser(result);
    }

    @DeleteMapping("/user/{id}")
    public UserEntity deleteUserById(
            @PathVariable(value = "id") String userId,
            @RequestHeader("user") String principal) {

        User result = userCmdCoordinator.deleteUser(principal, User.newBuilder()
                .setId(userId)
                .setVersion(Long.MAX_VALUE)
                .build());

        return UserEntity.fromAvroUser(result);
    }

    @ExceptionHandler(CmdException.class)
    public ResponseEntity handleCommandException(CmdException ex, Locale locale) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    //API Classes
    public static class UpdateUser {
        private String name;
        private String email;
        private Long version;

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

    }

    public static class CreateUser {
        private String name;
        private String email;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }
}


