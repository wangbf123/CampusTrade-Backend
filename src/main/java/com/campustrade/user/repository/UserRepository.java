package com.campustrade.user.repository;

import com.campustrade.user.model.User;

import java.util.Optional;

public interface UserRepository {

    User save(User user);

    Optional<User> findById(Long id);

    Optional<User> findByUsername(String username);
}
