package com.akine.activity.application;

import java.util.List;

/** Un pase con su ledger completo, del mas viejo al mas nuevo (RF-M29-009). */
public record PaseDetalleView(PaseView pase, List<MovimientoPaseView> movimientos) {
}
