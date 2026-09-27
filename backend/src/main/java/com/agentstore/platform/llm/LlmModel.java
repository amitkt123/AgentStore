package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One model the platform can serve. Operators set prices and {@code enabled}; nothing in code invents them. */
@Entity
@Table(name = "llm_model")
@Getter
@Setter
@NoArgsConstructor
public class LlmModel {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    private LlmVendor provider;

    private String displayName;
    private boolean providerRetainsData;
    private BigDecimal inputPricePerMtok;
    private BigDecimal outputPricePerMtok;
    private String currency;
    private boolean enabled;
}
