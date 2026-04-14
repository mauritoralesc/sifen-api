package com.ratones.sifenwrapper.repository;

import com.ratones.sifenwrapper.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByIdAndActiveTrue(Long id);

    List<Company> findAllByActiveTrue();
}
