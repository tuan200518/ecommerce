package com.shop.auth.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Page<UserAccount> findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrderByIdAsc(
            String email, String fullName, Pageable pageable);

    Page<UserAccount> findAllByOrderByIdAsc(Pageable pageable);
}
