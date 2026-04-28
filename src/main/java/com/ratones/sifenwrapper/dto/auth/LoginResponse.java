package com.ratones.sifenwrapper.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    private String role;
    private Long companyId;

    // Multi-company selection flow
    private Boolean requiresCompanySelection;
    private String selectionToken;
    private List<CompanyMemberInfo> companies;
}
