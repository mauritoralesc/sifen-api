package com.ratones.sifenwrapper.dto.auth;

import lombok.Data;

@Data
public class RefreshRequest {
    private String refreshToken;
}
