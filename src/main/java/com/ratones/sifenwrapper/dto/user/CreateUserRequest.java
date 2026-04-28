package com.ratones.sifenwrapper.dto.user;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String email;
    private String password;
    private String fullName;
    private String role; // ADMIN | USER
}
