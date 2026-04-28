package com.ratones.sifenwrapper.dto.user;

import lombok.Data;

@Data
public class AddMemberRequest {
    private String email;
    private String role; // ADMIN | USER
}
