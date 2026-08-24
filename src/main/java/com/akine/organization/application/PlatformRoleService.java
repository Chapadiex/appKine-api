package com.akine.organization.application;

import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.exception.PlatformRoleNotFoundException;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Alta, baja y listado del rol de plataforma (ADR-0020).
 *
 * <h2>El primero no nace por aca</h2>
 *
 * <p>El bootstrap es un seed de migracion (V15) que crea la cuenta institucional y su fila de
 * {@code platform_role} juntas. Esta clase administra los <b>siguientes</b>.
 *
 * <p>Que un {@code PLATFORM_ADMIN} pueda otorgarle el rol a otro tiene un costo que hay que
 * decir de frente: <b>comprometer una de esas cuentas compromete la plataforma entera</b>, porque
 * el atacante puede crearse persistencia. La alternativa —que cada alta posterior sea una
 * migracion o una operacion manual en la base— no es mejor: cambia un riesgo por otro (accesos
 * directos a produccion, sin auditoria y sin autor). Se eligio la via auditada, y por eso cada
 * alta y cada baja escriben {@code PLATFORM_ROLE_GRANTED} / {@code PLATFORM_ROLE_REVOKED} con
 * autor y motivo obligatorio.
 *
 * <h2>Sus eventos van sin tenant</h2>
 *
 * <p>{@code organizationId = null} en la auditoria: son eventos de plataforma, no de un tenant.
 * Es la forma que ADR-0019 ya admite para {@code audit_event}, y la unica correcta aca —elegir
 * un tenant cualquiera para rellenar la columna haria que el evento apareciera en la auditoria
 * de un cliente que no tuvo nada que ver—.
 */
@Service
public class PlatformRoleService {

	private static final Logger log = LoggerFactory.getLogger(PlatformRoleService.class);

	private final PlatformRoleRepositoryPort platformRoleRepository;
	private final PermissionEvaluator permissionEvaluator;
	private final AuditTrail auditTrail;

	public PlatformRoleService(
			PlatformRoleRepositoryPort platformRoleRepository,
			PermissionEvaluator permissionEvaluator,
			AuditTrail auditTrail) {
		this.platformRoleRepository = platformRoleRepository;
		this.permissionEvaluator = permissionEvaluator;
		this.auditTrail = auditTrail;
	}

	/**
	 * Otorga el rol de plataforma a una cuenta existente.
	 *
	 * <p>La existencia de la cuenta no se verifica aca y no puede verificarse: {@code cuenta} es
	 * de {@code identity} y {@code organization} no compila contra ese modulo. Es la misma
	 * situacion que el alta directa de membership, y se resuelve igual — la capa {@code api} de
	 * esta operacion vive del lado de {@code identity}, que resuelve el email y entra con el id.
	 *
	 * @throws AccessDeniedException si el actor no es administrador de plataforma (403)
	 * @throws IllegalStateException si esa cuenta ya tiene el rol vigente (409, traducido por el
	 *         advice a partir de la clave duplicada)
	 */
	@Transactional
	public PlatformRoleView grant(long actorAccountId, long targetAccountId, String motivo) {
		Instant ahora = Instant.now();
		exigirPlataforma(actorAccountId, ahora);
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Otorgar el permiso mas alto del sistema exige un motivo declarado");
		}

		PlatformRole rol = new PlatformRole(targetAccountId, actorAccountId, motivo, ahora);

		PlatformRole persistido;
		try {
			persistido = platformRoleRepository.saveAndFlush(rol);
		} catch (DataIntegrityViolationException yaLoTiene) {
			// uk_platform_role_activo. Sin volver a tocar la sesion JPA despues del flush
			// fallido: una sesion reusada ahi tira AssertionFailure y devuelve 500.
			throw new IllegalStateException(
					"Esa cuenta ya tiene el rol de plataforma vigente", yaLoTiene);
		}

		auditTrail.record(new AuditEntry(
				null, null, actorAccountId,
				AuditEvents.PLATFORM_ROLE_GRANTED, AuditEvents.ENTITY_PLATFORM_ROLE,
				persistido.getId(), null, persistido.getRoleCode().name(),
				Map.of("targetAccountId", String.valueOf(targetAccountId)), motivo,
				AuditEvents.correlationId(), ahora));

		log.info("Rol de plataforma otorgado: targetAccountId={} por accountId={}",
				targetAccountId, actorAccountId);
		return PlatformRoleView.de(persistido);
	}

	/**
	 * Revoca un rol de plataforma. Baja logica: la fila queda, con su autor y su motivo.
	 *
	 * <p><b>No impide quedarse sin ningun administrador de plataforma.</b> Es deliberado y hay
	 * que decirlo: el invariante de "ultimo admin" es del tenant, donde quedarse sin
	 * administrador deja a un cliente encerrado afuera de su propio centro. En la plataforma el
	 * rescate existe —es el seed de migracion, reproducible— y un invariante aca impediria
	 * revocar de urgencia una cuenta comprometida, que es el caso en el que revocar mas importa.
	 *
	 * @throws PlatformRoleNotFoundException si no existe o ya fue revocado (404)
	 */
	@Transactional
	public void revoke(long actorAccountId, long platformRoleId, String motivo) {
		Instant ahora = Instant.now();
		exigirPlataforma(actorAccountId, ahora);

		PlatformRole rol = platformRoleRepository.findByIdAndActiveTrue(platformRoleId)
				.orElseThrow(() -> new PlatformRoleNotFoundException(platformRoleId));

		rol.revoke(actorAccountId, motivo, ahora);
		platformRoleRepository.save(rol);

		auditTrail.record(new AuditEntry(
				null, null, actorAccountId,
				AuditEvents.PLATFORM_ROLE_REVOKED, AuditEvents.ENTITY_PLATFORM_ROLE,
				rol.getId(), rol.getRoleCode().name(), null,
				Map.of("targetAccountId", String.valueOf(rol.getAccountId())), motivo,
				AuditEvents.correlationId(), ahora));

		log.info("Rol de plataforma revocado: platformRoleId={} por accountId={}",
				platformRoleId, actorAccountId);
	}

	/** Roles de plataforma vigentes. Solo alcanzable desde las rutas de plataforma. */
	@Transactional(readOnly = true)
	public List<PlatformRoleView> list(long actorAccountId) {
		exigirPlataforma(actorAccountId, Instant.now());
		return platformRoleRepository.findAllByActiveTrue().stream()
				.map(PlatformRoleView::de)
				.toList();
	}

	private void exigirPlataforma(long actorAccountId, Instant ahora) {
		if (!permissionEvaluator.isPlatformAdmin(actorAccountId, ahora)) {
			throw new AccessDeniedException(
					"Operacion reservada a la administracion de plataforma");
		}
	}
}
