package com.ratones.sifenwrapper.dto.user;

import lombok.Data;

@Data
public class UpdateUserRequest {
    private String email;
    private String fullName;
    private String password;
}
