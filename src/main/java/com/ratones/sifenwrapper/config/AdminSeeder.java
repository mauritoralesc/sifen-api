package com.ratones.sifenwrapper.config;

import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.entity.User;
import com.ratones.sifenwrapper.entity.UserCompanyMembership;
import com.ratones.sifenwrapper.repository.CompanyRepository;
import com.ratones.sifenwrapper.repository.UserCompanyMembershipRepository;
import com.ratones.sifenwrapper.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final UserCompanyMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail("admin@synctema.com")) {
            log.debug("Admin user already exists, skipping seed");
            return;
        }

        log.info("Creando empresa y usuario admin por defecto...");

        Company company = companyRepository.save(Company.builder()
                .nombre("Administración")
                .ruc("00000000")
                .dv("0")
                .ambiente("DEV")
                .build());

        User admin = userRepository.save(User.builder()
                .email("admin@synctema.com")
                .passwordHash(passwordEncoder.encode("admin123"))
                .fullName("Administrador")
                .build());

        membershipRepository.save(UserCompanyMembership.builder()
                .user(admin)
                .company(company)
                .role(User.Role.ADMIN)
                .build());

        log.info("Usuario admin creado: admin@synctema.com / admin123");
        log.warn("⚠ Cambie la contraseña del admin en producción");
    }
}
