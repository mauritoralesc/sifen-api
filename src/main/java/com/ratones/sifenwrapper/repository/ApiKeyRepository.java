package com.ratones.sifenwrapper.repository;

import com.ratones.sifenwrapper.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    List<ApiKey> findAllByCompanyIdAndActiveTrue(Long companyId);
}
