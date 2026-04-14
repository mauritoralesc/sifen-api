package com.ratones.sifenwrapper.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sifen.batch")
public class BatchProperties {

    private boolean enabled = true;

    /** Intervalo de envío de lotes pendientes en ms (default: 60s) */
    private long sendInterval = 60000;

    /** Intervalo de consulta de lotes enviados en ms (default: 10 min) */
    private long pollInterval = 600000;

    /** Máximo de DEs por lote (SIFEN permite hasta 50) */
    private int maxPerLote = 50;

    /** Segundos mínimos de espera antes de consultar un lote enviado (default: 10 min) */
    private int minWaitBeforePoll = 600;

    /** Horas máximas para consultar un lote por WS consulta-lote. Después se consulta por CDC individual (default: 48h) */
    private int maxPollAgeHours = 48;
}
