package com.akine.activity.application;

import com.akine.activity.application.HonorariosViews.InstructorAdicionalView;
import com.akine.activity.application.HonorariosViews.InstructoresDeClaseView;
import com.akine.activity.domain.ClaseInstructor;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.EstadoClase;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.exception.ClaseConHonorariosLiquidadosException;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.activity.domain.port.HonorariosRepositoryPorts.ClaseInstructorRepositoryPort;
import com.akine.billing.spi.LiquidacionesDeHonorarios;
import com.akine.offering.spi.HabilitacionSnapshot;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Los instructores de una clase (AKINE-08.09b, DP-38): el principal de siempre y los adicionales.
 *
 * <p>Existe por el reparto de honorarios: el total de una clase se divide en partes iguales entre
 * sus instructores. <b>El adicional no ocupa agenda</b> —la exclusion de turnos y clases sigue
 * mirando solo al principal—, limite declarado en el diseno.
 *
 * <p>Reemplazar toma la clase {@code FOR UPDATE}, igual que reprogramar, y se niega si la clase ya
 * tiene honorarios liquidados: cambiar el reparto de una liquidacion cerrada dejaria su snapshot
 * contando otra historia.
 */
@Service
public class InstructoresDeClaseService {

	private static final Logger log = LoggerFactory.getLogger(InstructoresDeClaseService.class);

	private static final int MAXIMO_ADICIONALES = 10;

	private final ClaseProgramadaRepositoryPort clases;
	private final ClaseInstructorRepositoryPort instructores;
	private final LiquidacionesDeHonorarios liquidaciones;
	private final OfertaDirectory ofertas;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final AccountIdentityDirectory identidades;
	private final PermissionGuard permissionGuard;
	private final SoporteEnActividad soporte;
	private final AuditTrail auditTrail;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public InstructoresDeClaseService(
			ClaseProgramadaRepositoryPort clases,
			ClaseInstructorRepositoryPort instructores,
			LiquidacionesDeHonorarios liquidaciones,
			OfertaDirectory ofertas,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			AccountIdentityDirectory identidades,
			PermissionGuard permissionGuard,
			SoporteEnActividad soporte,
			AuditTrail auditTrail) {

		this.clases = clases;
		this.instructores = instructores;
		this.liquidaciones = liquidaciones;
		this.ofertas = ofertas;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.identidades = identidades;
		this.permissionGuard = permissionGuard;
		this.soporte = soporte;
		this.auditTrail = auditTrail;
	}

	/** Los instructores de la clase. Exige {@code clase:read}. */
	@Transactional(readOnly = true)
	public InstructoresDeClaseView ver(OperatingActor actor, long consultorioId, long claseId) {
		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.CLASE_READ);

		ClaseProgramada clase = clases.findByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
		return proyectar(organizationId, clase, instructores.vigentesDe(organizationId, claseId));
	}

	/**
	 * Reemplaza los instructores ADICIONALES. Exige {@code clase:manage}. Lista vacia los quita a
	 * todos. Idempotente: pedir la lista que ya esta no escribe nada.
	 *
	 * @throws IllegalArgumentException                membership repetida, el principal, ajena,
	 *                                                 no vigente o no habilitada para la oferta (400)
	 * @throws TransicionDeClaseNoPermitidaException   la clase esta cancelada (409)
	 * @throws ClaseConHonorariosLiquidadosException   la clase tiene honorarios liquidados (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public InstructoresDeClaseView reemplazar(
			OperatingActor actor, long consultorioId, long claseId, List<Long> adicionales) {

		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.CLASE_MANAGE);

		// FOR UPDATE antes de leer nada: serializa con otro reemplazo y con la confirmacion de una
		// liquidacion, que toma las mismas filas.
		ClaseProgramada clase = clases.lockByIdInScope(organizationId, consultorioId, claseId)
				.orElseThrow(() -> new ClaseNotAccessibleException(claseId));
		if (clase.getEstado() == EstadoClase.CANCELADA) {
			throw new TransicionDeClaseNoPermitidaException(
					claseId, "una clase cancelada no cambia instructores");
		}

		Set<Long> pedidos = validarPedido(organizationId, consultorioId, clase, adicionales);
		List<ClaseInstructor> vigentes = instructores.vigentesDe(organizationId, claseId);
		Set<Long> actuales = new TreeSet<>();
		vigentes.forEach(i -> actuales.add(i.getMembershipId()));
		if (actuales.equals(new TreeSet<>(pedidos))) {
			return proyectar(organizationId, clase, vigentes);
		}
		if (liquidaciones.claseLiquidada(organizationId, claseId)) {
			throw new ClaseConHonorariosLiquidadosException(claseId);
		}

		Instant ahora = Instant.now();
		for (ClaseInstructor vigente : vigentes) {
			if (!pedidos.contains(vigente.getMembershipId())) {
				vigente.remover(ahora, actor.accountId());
				instructores.save(vigente);
			}
		}
		for (Long membershipId : pedidos) {
			if (!actuales.contains(membershipId)) {
				instructores.save(new ClaseInstructor(
						organizationId, consultorioId, claseId, membershipId, ahora, actor.accountId()));
			}
		}

		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("anteriores", actuales.toString());
		detalle.put("nuevos", new TreeSet<>(pedidos).toString());
		auditTrail.record(new AuditEntry(
				organizationId, consultorioId, actor.accountId(), "CLASE_INSTRUCTORES_ACTUALIZADOS",
				"CLASE_PROGRAMADA", claseId, null, null, detalle, null, null, ahora));
		log.info("Instructores de la clase actualizados: claseId={} adicionales={}", claseId, pedidos);

		return proyectar(organizationId, clase, instructores.vigentesDe(organizationId, claseId));
	}

	private Set<Long> validarPedido(
			long organizationId, long consultorioId, ClaseProgramada clase, List<Long> adicionales) {

		Set<Long> pedidos = new LinkedHashSet<>();
		for (Long membershipId : adicionales) {
			if (membershipId == null || !pedidos.add(membershipId)) {
				throw new IllegalArgumentException("Un instructor adicional se indica una sola vez");
			}
			if (membershipId.equals(clase.getProfesionalMembershipId())) {
				throw new IllegalArgumentException(
						"El profesional principal de la clase no se repite como adicional");
			}
		}
		if (pedidos.size() > MAXIMO_ADICIONALES) {
			throw new IllegalArgumentException(
					"Una clase admite hasta " + MAXIMO_ADICIONALES + " instructores adicionales");
		}
		if (pedidos.isEmpty()) {
			return pedidos;
		}

		Map<Long, ConsultorioMembershipSnapshot> vinculos = memberships.findTodas(organizationId, pedidos);
		// LISTA VACIA SIGNIFICA TODOS, NO NINGUNO: la regla de V28, la misma que el principal.
		List<HabilitacionSnapshot> habilitaciones =
				ofertas.profesionalesHabilitados(organizationId, consultorioId, clase.getOfertaId());
		Instant ahora = Instant.now();
		for (Long membershipId : pedidos) {
			ConsultorioMembershipSnapshot vinculo = vinculos.get(membershipId);
			if (vinculo == null || !vinculo.validAt(ahora)
					|| (vinculo.consultorioId() != null && vinculo.consultorioId() != consultorioId)) {
				throw new IllegalArgumentException(
						"El instructor " + membershipId + " no tiene un vinculo vigente con la sede");
			}
			boolean habilitado = habilitaciones.isEmpty()
					|| habilitaciones.stream()
							.filter(h -> h.recursoId() == membershipId)
							.anyMatch(h -> h.vigenteEn(clase.getInicio()));
			if (!habilitado) {
				throw new IllegalArgumentException(
						"El instructor " + membershipId + " no esta habilitado para la oferta de la clase");
			}
		}
		return pedidos;
	}

	private InstructoresDeClaseView proyectar(
			long organizationId, ClaseProgramada clase, List<ClaseInstructor> adicionales) {

		Set<Long> ids = new LinkedHashSet<>();
		if (clase.getProfesionalMembershipId() != null) {
			ids.add(clase.getProfesionalMembershipId());
		}
		adicionales.forEach(i -> ids.add(i.getMembershipId()));
		Map<Long, String> nombres = nombres(organizationId, ids);

		List<InstructorAdicionalView> vista = new ArrayList<>();
		for (ClaseInstructor i : adicionales) {
			vista.add(new InstructorAdicionalView(i.getMembershipId(), nombres.get(i.getMembershipId()),
					i.getAgregadoEn()));
		}
		return new InstructoresDeClaseView(
				clase.getId(),
				clase.getProfesionalMembershipId(),
				clase.getProfesionalMembershipId() == null ? null : nombres.get(clase.getProfesionalMembershipId()),
				vista);
	}

	/** Membership -> cuenta -> nombre. Un nombre que no resuelve sale null, nunca falla. */
	private Map<Long, String> nombres(long organizationId, Collection<Long> membershipIds) {
		if (membershipIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, ConsultorioMembershipSnapshot> vinculos = memberships.findTodas(organizationId, membershipIds);
		Map<Long, AccountIdentity> cuentas = identidades.identidadesDe(
				vinculos.values().stream().map(ConsultorioMembershipSnapshot::accountId).toList());
		Map<Long, String> nombres = new LinkedHashMap<>();
		vinculos.forEach((id, vinculo) -> {
			AccountIdentity cuenta = cuentas.get(vinculo.accountId());
			if (cuenta != null && cuenta.nombreCompleto() != null) {
				nombres.put(id, cuenta.nombreCompleto());
			}
		});
		return nombres;
	}

	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La gestion de clases requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private void exigirSede(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigir(OperatingActor actor, long organizationId, long consultorioId, String permiso) {
		soporte.registrarSiCorresponde(permissionGuard.requirePermission(new PermissionQuery(
						actor.accountId(), permiso, organizationId, consultorioId, null, Instant.now())),
				actor.accountId(), organizationId, consultorioId, permiso);
	}
}
