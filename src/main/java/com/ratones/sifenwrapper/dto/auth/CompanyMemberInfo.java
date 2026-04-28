package com.ratones.sifenwrapper.dto.auth;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CompanyMemberInfo {
    private Long companyId;
    private String nombre;
    private String ruc;
    private String role;
}
