package com.akine.organization.application;

import com.akine.organization.domain.OrganizationIa;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.OrganizationIaRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.spi.FuncionIa;
import com.akine.organization.spi.FuncionesIaDelCentro;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Funciones de IA por centro. Dos capas: el despliegue decide si la funcion existe
 * ({@link IaDelDespliegue}) y el centro si la quiere ({@code organization_ia}). Activa = las dos.
 *
 * <p>Cada modulo de IA sigue chequeando su propio interruptor de despliegue y despues pregunta aca
 * por {@link FuncionesIaDelCentro}: apagada por el centro responde lo mismo que apagada por el
 * despliegue (409 o 404 segun el modulo), sin un tipo de error nuevo.
 */
@Service
public class FuncionesIaService implements FuncionesIaDelCentro {

	private final OrganizationRepositoryPort organizationRepository;
	private final OrganizationIaRepositoryPort iaRepository;
	private final IaDelDespliegue despliegue;
	private final AuditTrail auditTrail;

	public FuncionesIaService(
			OrganizationRepositoryPort organizationRepository,
			OrganizationIaRepositoryPort iaRepository,
			IaDelDespliegue despliegue,
			AuditTrail auditTrail) {
		this.organizationRepository = organizationRepository;
		this.iaRepository = iaRepository;
		this.despliegue = despliegue;
		this.auditTrail = auditTrail;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean prendida(long organizationId, FuncionIa funcion) {
		return iaRepository.findByOrganizationId(organizationId)
				.map(ia -> delCentro(ia, funcion))
				.orElse(true);
	}

	@Transactional(readOnly = true)
	public FuncionesIaView ver(long organizationId) {
		exigirVigente(organizationId);
		return vista(organizationId, iaRepository.findByOrganizationId(organizationId));
	}

	/**
	 * Reemplaza lo que el centro quiere usar. Prender una funcion que el despliegue no ofrece se
	 * guarda igual: queda lista para cuando el servidor la tenga.
	 *
	 * @throws OptimisticLockingFailureException la version quedo vieja (409 concurrent-modification)
	 */
	@Transactional
	public FuncionesIaView actualizar(long organizationId, boolean asistente, boolean evolucion,
			boolean sugerencias, boolean biblioteca, long versionEsperada, Long actorAccountId) {
		exigirVigente(organizationId);
		iaRepository.asegurarFila(organizationId);
		OrganizationIa ia = iaRepository.findByOrganizationId(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		if (ia.getVersion() != versionEsperada) {
			throw new OptimisticLockingFailureException("Las funciones de IA fueron modificadas por otra operacion");
		}
		ia.reemplazar(asistente, evolucion, sugerencias, biblioteca);
		OrganizationIa guardada = iaRepository.saveAndFlush(ia);

		// Las funciones van como VALOR y no como clave: AuditTrailImpl tacha toda clave que contenga
		// "evolucion" (contenido clinico), y una clave "evolucion" se guardaba como [REDACTADO].
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("prendidas", String.join(",", Arrays.stream(FuncionIa.values())
				.filter(f -> propia(guardada, f)).map(Enum::name).toList()));
		detalles.put("apagadas", String.join(",", Arrays.stream(FuncionIa.values())
				.filter(f -> !propia(guardada, f)).map(Enum::name).toList()));
		auditTrail.record(new AuditEntry(
				organizationId, null, actorAccountId, AuditEvents.ORGANIZATION_IA_UPDATED,
				AuditEvents.ENTITY_ORGANIZATION, organizationId, null, null, detalles, null,
				AuditEvents.correlationId(), Instant.now()));

		return vista(organizationId, Optional.of(guardada));
	}

	private FuncionesIaView vista(long organizationId, Optional<OrganizationIa> ia) {
		List<FuncionesIaView.Funcion> funciones = Arrays.stream(FuncionIa.values())
				.map(f -> {
					boolean centro = ia.map(i -> propia(i, f)).orElse(true);
					boolean activa = despliegue.ofrece(f) && ia.map(i -> delCentro(i, f)).orElse(true);
					return new FuncionesIaView.Funcion(f, despliegue.ofrece(f), centro, activa);
				})
				.toList();
		return new FuncionesIaView(organizationId, ia.map(OrganizationIa::getVersion).orElse(0L), funciones);
	}

	/** Lo que el centro eligio para esa funcion sola, sin sus dependencias. */
	private static boolean propia(OrganizationIa ia, FuncionIa funcion) {
		return switch (funcion) {
			case ASISTENTE -> ia.isAsistente();
			case EVOLUCION -> ia.isEvolucion();
			case SUGERENCIAS -> ia.isSugerencias();
			case BIBLIOTECA -> ia.isBiblioteca();
		};
	}

	/** Lo que el centro eligio, con las dependencias: las sugerencias exigen evolucion y biblioteca. */
	private static boolean delCentro(OrganizationIa ia, FuncionIa funcion) {
		if (funcion == FuncionIa.SUGERENCIAS) {
			return ia.isSugerencias() && ia.isEvolucion() && ia.isBiblioteca();
		}
		return propia(ia, funcion);
	}

	private void exigirVigente(long organizationId) {
		if (organizationRepository.findByIdAndActiveTrue(organizationId).isEmpty()) {
			throw new OrganizationNotFoundException(organizationId);
		}
	}
}
