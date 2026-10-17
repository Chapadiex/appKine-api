package com.akine.activity.application;

import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lo que la vista de un pase calcula al leer y no se persiste ({@link PaseView.Uso}): creditos
 * usados y cobros (del ledger y de {@code billing}), el periodo que lo renovo y el nombre de quien
 * autorizo la renovacion.
 *
 * <p><b>Siempre en lote.</b> Para N pases son cuatro consultas en total —ledger, cobros,
 * renovaciones e identidades—, nunca una por pase: el listado de pases de una persona es el
 * consumidor que lo exige.
 *
 * <p><b>El nombre es el de hoy</b>, resuelto por {@code platform.spi.identity} desde la cuenta que
 * guarda {@code renovacion_autorizada_por_cuenta_id} (V103), igual que el autor de una version de
 * sesion. No se duplica en la fila: dos verdades que divergen en cuanto alguien corrige un apellido.
 * Si la cuenta no resuelve, el nombre viaja ausente.
 */
@Component
public class LecturaDePases {

	private final PaseServicioRepositoryPort pases;
	private final MovimientoPaseRepositoryPort movimientos;
	private final DevengoDeVentas ventas;
	private final AccountIdentityDirectory identidades;

	public LecturaDePases(
			PaseServicioRepositoryPort pases,
			MovimientoPaseRepositoryPort movimientos,
			DevengoDeVentas ventas,
			AccountIdentityDirectory identidades) {

		this.pases = pases;
		this.movimientos = movimientos;
		this.ventas = ventas;
		this.identidades = identidades;
	}

	/** El uso de varios pases, por id. */
	public Map<Long, PaseView.Uso> usos(long organizationId, Collection<PaseServicio> lista) {
		if (lista.isEmpty()) {
			return Map.of();
		}
		List<Long> ids = lista.stream().map(PaseServicio::getId).toList();
		Map<Long, Integer> neto = movimientos.consumoNetoPorPase(organizationId, ids);
		Set<Long> conCobros = ventas.ventasConCobros(organizationId, ids);
		Map<Long, Long> renovaciones = pases.renovacionesDe(organizationId, ids);
		Map<Long, String> nombres = nombres(lista.stream()
				.map(PaseServicio::getRenovacionAutorizadaPorCuentaId).toList());

		Map<Long, PaseView.Uso> usos = new HashMap<>();
		for (PaseServicio pase : lista) {
			usos.put(pase.getId(), new PaseView.Uso(
					-neto.getOrDefault(pase.getId(), 0),
					conCobros.contains(pase.getId()),
					renovaciones.get(pase.getId()),
					// Sin cuenta, null y no get(null): un Map inmutable lo rechaza con NPE.
					pase.getRenovacionAutorizadaPorCuentaId() == null
							? null
							: nombres.get(pase.getRenovacionAutorizadaPorCuentaId())));
		}
		return usos;
	}

	/** El uso de un pase. */
	public PaseView.Uso uso(long organizationId, PaseServicio pase) {
		return usos(organizationId, List.of(pase)).get(pase.getId());
	}

	/**
	 * Un pase con uso y cobros ya conocidos por quien llama (recien vendido, o recien anulado sin
	 * cobros): solo resuelve la renovacion y el nombre.
	 */
	public PaseView.Uso uso(long organizationId, PaseServicio pase, int creditosUsados, boolean conCobros) {
		Long renovadoPor = pases.renovacionesDe(organizationId, List.of(pase.getId())).get(pase.getId());
		return new PaseView.Uso(creditosUsados, conCobros, renovadoPor,
				nombreDe(pase.getRenovacionAutorizadaPorCuentaId()));
	}

	/** El nombre de una cuenta, o {@code null} si no resuelve o no hay cuenta. */
	public String nombreDe(Long cuentaId) {
		return cuentaId == null ? null : nombres(List.of(cuentaId)).get(cuentaId);
	}

	private Map<Long, String> nombres(Collection<Long> cuentaIds) {
		List<Long> conCuenta = cuentaIds.stream().filter(Objects::nonNull).distinct().toList();
		if (conCuenta.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> nombres = new HashMap<>();
		for (AccountIdentity identidad : identidades.identidadesDe(conCuenta).values()) {
			if (identidad.nombreCompleto() != null && !identidad.nombreCompleto().isBlank()) {
				nombres.put(identidad.accountId(), identidad.nombreCompleto());
			}
		}
		return nombres;
	}
}
