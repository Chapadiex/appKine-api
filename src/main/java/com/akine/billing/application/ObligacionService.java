package com.akine.billing.application;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.exception.ConsultorioNoAccesibleException;
import com.akine.billing.domain.exception.ObligacionNotAccessibleException;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.AlertasDeConsumo;
import com.akine.person.spi.ConsumoARevisar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Consulta y anulacion de deuda (M18, RF-M18-004..007).
 *
 * <h2>Esta clase no devenga</h2>
 *
 * <p>La deuda nace del cierre de una atencion, y quien la crea es
 * {@code billing.infrastructure.ObligacionDevengador} reaccionando al cierre. No hay ningun
 * endpoint que cree una obligacion a mano: una deuda sin prestacion que la respalde es un cargo
 * que nadie puede justificar, y el snapshot que la explica solo existe en el momento del cierre.
 *
 * <h2>Deuda, cobro y caja son tres cosas</h2>
 *
 * <p>Es la regla maestra de M18/M19/M20 y el error que el UML de 2019 cometia. Aca no hay medio de
 * pago ni movimiento: el cobro es AKINE-07.02 y la caja AKINE-07.03, que quedo fuera del Paquete B.
 */
@Service
public class ObligacionService {

	private static final Logger log = LoggerFactory.getLogger(ObligacionService.class);

	private final ObligacionRepositoryPort obligaciones;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AlertasDeConsumo alertasDeConsumo;

	public ObligacionService(
			ObligacionRepositoryPort obligaciones,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AlertasDeConsumo alertasDeConsumo) {

		this.obligaciones = obligaciones;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.alertasDeConsumo = alertasDeConsumo;
	}

	/**
	 * La cuenta corriente de un paciente en la organizacion.
	 *
	 * <p>El alcance es la ORGANIZACION y no la sede, aunque el permiso se evalue con la sede del
	 * contexto: la deuda de un paciente es una sola aunque se haya generado en dos sedes del mismo
	 * centro, y mostrarla partida obligaria al administrativo a sumar de memoria. Es la misma
	 * decision que 03.01 tomo con el padron.
	 */
	@Transactional(readOnly = true)
	public List<ObligacionView> deLaPersona(OperatingActor actor, long consultorioId, long personaId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		return obligaciones.findDeLaPersona(organizationId, personaId).stream()
				.map(ObligacionView::de)
				.toList();
	}

	@Transactional(readOnly = true)
	public ObligacionView ver(OperatingActor actor, long consultorioId, long obligacionId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		return obligaciones.findByIdInScope(organizationId, consultorioId, obligacionId)
				.map(ObligacionView::de)
				.orElseThrow(() -> new ObligacionNotAccessibleException(obligacionId));
	}

	/**
	 * Anula una deuda con motivo obligatorio.
	 *
	 * <p><b>El motivo no es opcional, a diferencia de otras bajas del sistema.</b> Una deuda que se
	 * borra sin explicacion es exactamente lo que una auditoria busca: alguien anulo un cargo y no
	 * hay forma de saber si fue un error de carga, una cortesia o algo peor.
	 *
	 * <p>La version viaja porque la anulacion compite con la imputacion de un cobro: si alguien paga
	 * mientras otro anula, el que llega segundo tiene que enterarse. El control de
	 * {@code Obligacion#anular} sobre el saldo lo respalda del lado del dominio.
	 */
	@Transactional
	public ObligacionView anular(
			OperatingActor actor, long consultorioId, long obligacionId,
			String motivo, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);

		Obligacion obligacion = obligaciones.findByIdInScope(organizationId, consultorioId, obligacionId)
				.orElseThrow(() -> new ObligacionNotAccessibleException(obligacionId));

		if (obligacion.getVersion() != expectedVersion) {
			throw new org.springframework.dao.OptimisticLockingFailureException(
					"La obligacion " + obligacionId + " cambio desde que se leyo: version "
							+ expectedVersion + " contra " + obligacion.getVersion());
		}

		Instant ahora = Instant.now();
		obligacion.anular(motivo, ahora, actor.accountId());
		log.info("Obligacion anulada: obligacionId={} motivo={}", obligacionId, motivo);
		Obligacion anulada = obligaciones.save(obligacion);

		// DP-13 (AKINE C-4). Si la sesion de esta deuda consumio autorizaciones, quedan alertadas
		// "consumo a revisar". NO se devuelve la unidad: anular la deuda no prueba que la
		// prestacion no ocurrio. Misma transaccion: si la anulacion no commitea, no hay alerta.
		if (anulada.getSesionId() != null) {
			alertasDeConsumo.consumoARevisar(new ConsumoARevisar(
					organizationId, anulada.getSesionId(), anulada.getId(), motivo, ahora,
					actor.accountId()));
		}
		return ObligacionView.de(anulada);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La consulta de deuda requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	/**
	 * Exige {@code cobro:register} con la sede como alcance.
	 *
	 * <p>El mismo codigo cubre ver la deuda y anularla. Separar "ver" de "anular" exigiria un
	 * permiso mas que la matriz §5 no tiene, y quien puede cobrar necesariamente puede ver contra
	 * que cobra.
	 */
	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.COBRO_REGISTER,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}
}
