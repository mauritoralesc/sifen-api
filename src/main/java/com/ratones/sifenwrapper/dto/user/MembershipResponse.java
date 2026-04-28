package com.ratones.sifenwrapper.dto.user;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MembershipResponse {
    private Long userId;
    private String email;
    private String fullName;
    private String role;
    private boolean active;
    private LocalDateTime memberSince;
}
