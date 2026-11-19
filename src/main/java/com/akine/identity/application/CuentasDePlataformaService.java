package com.akine.identity.application;

import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cuentas por estado para el panel de la plataforma (19/11/2026). */
@Service
public class CuentasDePlataformaService {

	/** Cuentas vigentes por estado; una dada de baja no cuenta en ninguno. */
	public record Resumen(long activas, long pendientesDeActivar, long bloqueadas) {
	}

	private final CuentaRepositoryPort cuentas;

	CuentasDePlataformaService(CuentaRepositoryPort cuentas) {
		this.cuentas = cuentas;
	}

	@Transactional(readOnly = true)
	public Resumen resumen() {
		return new Resumen(
				cuentas.contarVigentesPorEstado(EstadoCuenta.ACTIVA),
				cuentas.contarVigentesPorEstado(EstadoCuenta.PENDIENTE_ACTIVACION),
				cuentas.contarVigentesPorEstado(EstadoCuenta.BLOQUEADA));
	}
}
