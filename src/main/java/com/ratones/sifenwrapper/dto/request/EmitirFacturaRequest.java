package com.ratones.sifenwrapper.dto.request;

import lombok.Data;

@Data
public class EmitirFacturaRequest {
    private ParamsDTO params;
    private DataDTO data;
    private QrDTO qr;
    private boolean includeKude = false;
}
