package com.ratones.sifenwrapper.repository;

import com.ratones.sifenwrapper.entity.UserCompanyMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserCompanyMembershipRepository extends JpaRepository<UserCompanyMembership, Long> {

    List<UserCompanyMembership> findAllByUserIdAndActiveTrue(Long userId);

    List<UserCompanyMembership> findAllByCompanyIdAndActiveTrue(Long companyId);

    Optional<UserCompanyMembership> findByUserIdAndCompanyIdAndActiveTrue(Long userId, Long companyId);

    boolean existsByUserIdAndCompanyIdAndActiveTrue(Long userId, Long companyId);
}
