package com.akine.activity.application;

import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.RenovacionAutorizada;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * La renovacion automatica de un abono se apaga sola cuando quien la autorizo ya no puede vender
 * (DP-40, U-43), y se le avisa al administrador.
 *
 * <h2>Por que</h2>
 *
 * <p>Cada periodo renovado se vende a nombre de quien prendio la renovacion
 * ({@code renovacion_autorizada_por_cuenta_id}): el job no es una persona y la deuda tiene que
 * tener a alguien que la haya autorizado. Si esa persona se fue del centro, o ya no tiene
 * {@code pase:manage} en la sede, seguir vendiendo a su nombre seria inventarle una autorizacion.
 *
 * <h2>Dos puertas, una regla</h2>
 *
 * <ol>
 *   <li><b>Al desvincular</b> ({@link #alPerderAlcance}): {@code organization} avisa por
 *       {@code ReaccionALaDesvinculacion} cuando revoca una membership, le cambia el rol o la sede, o
 *       le quita un permiso adicional. Corre en esa misma transaccion, con el cambio ya guardado.</li>
 *   <li><b>Antes de cada renovacion</b> ({@link #puedeVender} desde {@link RenovacionDeAbonos}):
 *       cubre lo que no pasa por un evento —un permiso adicional que vence solo, una membership que
 *       se suspende— y la que se le escapara a la primera puerta.</li>
 * </ol>
 *
 * <p>Las dos <b>vuelven a evaluar el permiso</b> con {@link PermissionEvaluator} en la sede del abono,
 * en vez de inferirlo del evento: una cuenta con dos memberships que pierde una puede seguir
 * vendiendo en la otra sede, y un cambio de rol a otro que tambien vende no apaga nada.
 *
 * <h2>Idempotente</h2>
 *
 * <p>Apagar es el {@code UPDATE ... WHERE renovacion_automatica = 1} de
 * {@code omitirRenovacion}: una segunda llamada no encuentra nada que apagar, no audita y no avisa.
 * El motivo queda en {@code renovacion_omitida_motivo} y el hecho en {@code ABONO_RENOVACION_APAGADA}.
 * Volver a prenderla es {@code configurarRenovacionDeAbono}, a nombre de quien la prenda.
 */
@Component
public class ApagadoDeRenovaciones {

	private static final Logger log = LoggerFactory.getLogger(ApagadoDeRenovaciones.class);

	static final String MOTIVO = "quien autorizo la renovacion ya no puede vender en la sede";
	static final String EVENTO = "ABONO_RENOVACION_APAGADA";
	private static final String ROL_ADMINISTRADOR = "ORG_ADMIN";
	private static final String CAUSA_DEL_JOB = "AUTORIZANTE_SIN_PERMISO";

	private final PaseServicioRepositoryPort pases;
	private final PermissionEvaluator permisos;
	private final ConsultorioMembershipDirectory memberships;
	private final ConsultorioDirectory consultorios;
	private final AccountIdentityDirectory identidades;
	private final NotificationOutbox outbox;
	private final AuditTrail auditTrail;

	public ApagadoDeRenovaciones(
			PaseServicioRepositoryPort pases,
			PermissionEvaluator permisos,
			ConsultorioMembershipDirectory memberships,
			ConsultorioDirectory consultorios,
			AccountIdentityDirectory identidades,
			NotificationOutbox outbox,
			AuditTrail auditTrail) {

		this.pases = pases;
		this.permisos = permisos;
		this.memberships = memberships;
		this.consultorios = consultorios;
		this.identidades = identidades;
		this.outbox = outbox;
		this.auditTrail = auditTrail;
	}

	/** {@code true} si la cuenta tiene hoy {@code pase:manage} en la sede: el permiso de vender. */
	public boolean puedeVender(long cuentaId, long organizationId, long consultorioId, Instant ahora) {
		return permisos.evaluate(new PermissionQuery(
				cuentaId, PermissionCodes.PASE_MANAGE, organizationId, consultorioId, null, ahora)).granted();
	}

	/**
	 * La cuenta perdio alcance en la organizacion: apaga la renovacion de los abonos que autorizo en
	 * las sedes donde ya no puede vender, y avisa a los administradores de cada una. Corre en la
	 * transaccion de quien desvincula.
	 *
	 * @param actorCuentaId quien desvinculo, para la auditoria
	 * @return cuantas renovaciones apago
	 */
	public int alPerderAlcance(
			long organizationId, long cuentaId, String causa, long actorCuentaId, Instant ahora) {

		List<RenovacionAutorizada> autorizadas = pases.renovacionesAutorizadasPor(organizationId, cuentaId);
		Map<Long, List<RenovacionAutorizada>> porSede = new TreeMap<>();
		for (RenovacionAutorizada r : autorizadas) {
			porSede.computeIfAbsent(r.consultorioId(), sede -> new ArrayList<>()).add(r);
		}
		int apagadas = 0;
		for (Map.Entry<Long, List<RenovacionAutorizada>> sede : porSede.entrySet()) {
			long consultorioId = sede.getKey();
			if (puedeVender(cuentaId, organizationId, consultorioId, ahora)) {
				continue;
			}
			List<Long> deLaSede = new ArrayList<>();
			for (RenovacionAutorizada r : sede.getValue()) {
				if (apagar(organizationId, consultorioId, r.paseId(), cuentaId, causa, actorCuentaId, ahora)) {
					deLaSede.add(r.paseId());
				}
			}
			if (!deLaSede.isEmpty()) {
				avisar(organizationId, consultorioId, cuentaId, deLaSede, ahora);
				apagadas += deLaSede.size();
			}
		}
		if (apagadas > 0) {
			log.info("Renovaciones de abonos apagadas por desvinculacion: organizationId={} cuentaId={} causa={} "
					+ "cantidad={}", organizationId, cuentaId, causa, apagadas);
		}
		return apagadas;
	}

	/**
	 * Lo mismo para un abono que el job encontro con un autorizante que ya no puede vender. Corre en
	 * la transaccion del job, con el abono bloqueado.
	 *
	 * @return {@code true} si la apago (si ya estaba apagada, nada)
	 */
	public boolean alRenovarSinPermiso(PaseServicio abono, Instant ahora) {
		long organizationId = abono.getOrganizationId();
		long consultorioId = abono.getConsultorioId();
		long cuentaId = abono.getRenovacionAutorizadaPorCuentaId();
		if (!apagar(organizationId, consultorioId, abono.getId(), cuentaId, CAUSA_DEL_JOB, null, ahora)) {
			return false;
		}
		avisar(organizationId, consultorioId, cuentaId, List.of(abono.getId()), ahora);
		log.info("Renovacion de abono apagada: paseId={} el autorizante ya no puede vender", abono.getId());
		return true;
	}

	private boolean apagar(
			long organizationId, long consultorioId, long paseId, long cuentaId, String causa,
			Long actorCuentaId, Instant ahora) {

		if (pases.omitirRenovacion(organizationId, paseId, MOTIVO) != 1) {
			return false;
		}
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("causa", causa);
		detalles.put("autorizadaPorCuentaId", String.valueOf(cuentaId));
		auditTrail.record(new AuditEntry(
				organizationId, consultorioId, actorCuentaId, EVENTO, "PASE_SERVICIO", paseId,
				"RENOVACION_ACTIVA", "RENOVACION_INACTIVA", detalles, MOTIVO, null, ahora));
		return true;
	}

	/**
	 * Un aviso por administrador de la sede, con cuantos abonos y quien los habia autorizado. Sin
	 * correo, o sin administradores, no se avisa: la renovacion queda apagada igual y el motivo esta
	 * en el abono y en la auditoria.
	 */
	private void avisar(long organizationId, long consultorioId, long cuentaId, List<Long> paseIds, Instant ahora) {
		Set<Long> administradores = new LinkedHashSet<>();
		for (ConsultorioMembershipSnapshot m : memberships.findPorRolEnSede(organizationId, consultorioId,
				ROL_ADMINISTRADOR)) {
			if (m.validAt(ahora) && m.accountId() != cuentaId) {
				administradores.add(m.accountId());
			}
		}
		if (administradores.isEmpty()) {
			log.warn("Renovacion de abonos apagada sin administrador a quien avisar: organizationId={} "
					+ "consultorioId={}", organizationId, consultorioId);
			return;
		}
		Set<Long> cuentas = new LinkedHashSet<>(administradores);
		cuentas.add(cuentaId);
		Map<Long, AccountIdentity> nombres = identidades.identidadesDe(cuentas);
		String sede = consultorios.find(organizationId, consultorioId).map(ConsultorioSnapshot::name).orElse(null);
		AccountIdentity autorizante = nombres.get(cuentaId);
		// La clave es la del hecho: la sede, el primer abono y el instante. Un reintento de la misma
		// transaccion no duplica; otra desvinculacion que apague otros abonos es otro hecho.
		String hecho = "renovacion-apagada:" + organizationId + ":" + paseIds.get(0) + ":" + ahora.toEpochMilli();

		for (long administrador : administradores) {
			AccountIdentity destino = nombres.get(administrador);
			if (destino == null || destino.email() == null || destino.email().isBlank()) {
				continue;
			}
			Map<String, String> datos = new LinkedHashMap<>();
			datos.put("nombre", destino.nombreCompleto());
			datos.put("consultorioNombre", sede);
			datos.put("abonosAfectados", String.valueOf(paseIds.size()));
			datos.put("renovacionAutorizadaPor", autorizante == null ? null : autorizante.nombreCompleto());
			outbox.enqueue(new NotificationEnqueueCommand(NotificationType.ABONO_RENOVACION_APAGADA,
					destino.email(), hecho + ":" + administrador, organizationId, null, aceptados(datos)));
		}
	}

	/** Solo lo que el outbox acepta: un nombre que no pasa la lista blanca no hace fallar el apagado. */
	private Map<String, String> aceptados(Map<String, String> datos) {
		Map<String, String> aceptados = new LinkedHashMap<>();
		datos.forEach((clave, valor) -> {
			if (valor == null || valor.isBlank()) {
				return;
			}
			try {
				outbox.validarDatosDeRender(Map.of(clave, valor));
				aceptados.put(clave, valor);
			} catch (IllegalArgumentException rechazado) {
				log.warn("Dato de render omitido en {}: clave={}", NotificationType.ABONO_RENOVACION_APAGADA, clave);
			}
		});
		return aceptados;
	}
}
