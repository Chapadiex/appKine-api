package com.akine.billing.infrastructure;

import com.akine.billing.domain.LiquidacionHonorarioClase;
import org.springframework.data.jpa.repository.JpaRepository;

/** Alta del detalle de una liquidacion de honorarios (AKINE-08.09b). Las lecturas van por el otro. */
interface LiquidacionHonorarioClaseRepository extends JpaRepository<LiquidacionHonorarioClase, Long> {
}
