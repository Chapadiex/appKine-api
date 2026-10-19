package com.akine.contracting.application;

import com.akine.contracting.domain.Financiador;
import com.akine.contracting.domain.FinanciadorCatalogo;
import com.akine.contracting.domain.PlanCatalogo;
import com.akine.contracting.domain.PlanCobertura;
import com.akine.contracting.domain.exception.FinanciadorCodigoTakenException;
import com.akine.contracting.domain.exception.FinanciadorCuitTakenException;
import com.akine.contracting.domain.exception.FinanciadorNombreTakenException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.FinanciadorYaInactivoException;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorCatalogoRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.FinanciadorRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCatalogoRepositoryPort;
import com.akine.contracting.domain.port.ContractingRepositoryPorts.PlanCoberturaRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Un centro ADOPTA un financiador del catalogo global (DP-43): se crea una copia propia en
 * {@code financiador} con sus planes activos en {@code plan_cobertura}, con el puntero informativo
 * {@code catalogo_id} a la fila de origen.
 *
 * <h2>Idempotente, y sin ventana</h2>
 *
 * <p>Adoptar dos veces el mismo devuelve la copia vigente que ya existe, sin escribir nada. La
 * lectura previa resuelve el caso comun; el caso que rompe el diseno —dos adopciones simultaneas
 * del mismo centro— lo resuelven los uniques de {@code financiador} (el de codigo choca primero;
 * {@code uk_financiador_catalogo_vigente} es la garantia aunque la copia cambie de codigo): la
 * segunda espera el lock del indice, choca al commitear la primera, su transaccion se deshace
 * entera (planes incluidos) y relee la copia ganadora en una transaccion NUEVA en
 * {@code READ_COMMITTED}. Hay copia: se devuelve. No la hay: era un financiador propio del centro
 * con el mismo codigo, nombre o CUIT, y va el 409. Las dos reglas que
 * esto respeta, ya pagadas en el proyecto: despues de un flush fallido no se vuelve a tocar la
 * sesion JPA, y la relectura que resuelve un choque de unique va en {@code READ_COMMITTED} —bajo
 * {@code REPEATABLE READ} la foto previa no ve la fila del ganador—.
 *
 * <h2>Lo que la copia NO hace</h2>
 *
 * <ul>
 *   <li>No se re-sincroniza: editar el catalogo despues no la toca. La lectura del catalogo para el
 *       centro avisa {@code actualizacionDisponible} y no aplica nada.</li>
 *   <li>No reemplaza a un financiador que el centro ya cargo a mano con el mismo codigo, nombre o
 *       CUIT: eso es 409 con el {@code type} de siempre, y el centro decide.</li>
 * </ul>
 */
@Service
public class AdopcionDeFinanciadorService {

	private static final Logger log = LoggerFactory.getLogger(AdopcionDeFinanciadorService.class);

	private final FinanciadorCatalogoRepositoryPort catalogo;
	private final PlanCatalogoRepositoryPort planesCatalogo;
	private final FinanciadorRepositoryPort financiadores;
	private final PlanCoberturaRepositoryPort planes;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final TransactionTemplate relectura;

	public AdopcionDeFinanciadorService(
			FinanciadorCatalogoRepositoryPort catalogo,
			PlanCatalogoRepositoryPort planesCatalogo,
			FinanciadorRepositoryPort financiadores,
			PlanCoberturaRepositoryPort planes,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {

		this.catalogo = catalogo;
		this.planesCatalogo = planesCatalogo;
		this.financiadores = financiadores;
		this.planes = planes;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.transaccion.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.relectura = new TransactionTemplate(transactionManager);
		this.relectura.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.relectura.setReadOnly(true);
	}

	/**
	 * Adopta el financiador {@code catalogoId} del catalogo global para la organizacion del
	 * contexto. {@code convenio:manage} sobre la sede del contexto, igual que dar de alta un
	 * financiador a mano: el efecto es el mismo.
	 */
	public AdopcionView adoptar(OperatingActor actor, long catalogoId) {
		try {
			return transaccion.execute(estado -> adoptarEnTransaccion(actor, catalogoId));
		} catch (ChoqueAlAdoptar choque) {
			// La transaccion ya se deshizo entera. Si el choque fue contra la copia que otra
			// adopcion simultanea del mismo centro acaba de commitear, se devuelve esa; si no, era
			// un financiador que el centro ya tenia cargado a mano, y va su 409.
			long organizationId = actor.contextOrganizationId();
			return relectura.execute(estado -> financiadores
					.findByOrganizationIdAndCatalogoIdAndDeletedAtIsNull(organizationId, catalogoId)
					.map(copia -> {
						log.info("Adopcion concurrente resuelta releyendo: catalogoId={}", catalogoId);
						return vista(copia, false);
					})
					.orElseThrow(() -> choque.traducido));
		}
	}

	private AdopcionView adoptarEnTransaccion(OperatingActor actor, long catalogoId) {
		AutorizacionDeCatalogo.exigirGestionDelCatalogo(
				permissionGuard, actor, "Adoptar un financiador del catalogo global");
		long organizationId = actor.contextOrganizationId();

		FinanciadorCatalogo origen = catalogo.findById(catalogoId)
				.orElseThrow(() -> new FinanciadorNotAccessibleException(catalogoId));

		// La idempotencia va ANTES de mirar el estado del origen: un centro que adopto algo que la
		// plataforma dio de baja despues sigue recibiendo su copia, no un 409.
		var yaAdoptado =
				financiadores.findByOrganizationIdAndCatalogoIdAndDeletedAtIsNull(organizationId, catalogoId);
		if (yaAdoptado.isPresent()) {
			return vista(yaAdoptado.get(), false);
		}
		if (!origen.isOperable()) {
			throw new FinanciadorYaInactivoException(catalogoId, "adoptar");
		}

		Financiador copia;
		try {
			copia = financiadores.saveAndFlush(Financiador.adoptado(organizationId, origen));
		} catch (DataIntegrityViolationException choque) {
			throw traducir(choque, origen.getCodigo());
		}

		List<PlanCatalogo> planesDelOrigen = planesCatalogo
				.findAllByFinanciadorCatalogoIdInOrderByNombreAsc(List.of(catalogoId))
				.stream()
				.filter(PlanCatalogo::isOperable)
				.toList();
		for (PlanCatalogo plan : planesDelOrigen) {
			planes.save(PlanCobertura.adoptado(organizationId, copia.getId(), plan));
		}

		auditTrail.record(new AuditEntry(
				organizationId,
				null,
				actor.accountId(),
				AuditEvents.FINANCIADOR_ADOPTADO,
				AuditEvents.ENTITY_FINANCIADOR,
				copia.getId(),
				null,
				"ACTIVO",
				Map.of("catalogoId", String.valueOf(catalogoId),
						"codigo", copia.getCodigo(),
						"planes", String.valueOf(planesDelOrigen.size())),
				null,
				AuditEvents.correlationId(),
				Instant.now()));

		log.info("Financiador adoptado del catalogo: catalogoId={} financiadorId={} planes={}",
				catalogoId, copia.getId(), planesDelOrigen.size());
		return vista(copia, true);
	}

	private AdopcionView vista(Financiador copia, boolean creado) {
		LocalDate hoy = LocalDate.now();
		List<PlanCoberturaView> planesDeLaCopia = planes
				.findAllByOrganizationIdAndFinanciadorIdOrderByNombreAsc(
						copia.getOrganizationId(), copia.getId())
				.stream()
				.map(plan -> PlanCoberturaView.de(plan, hoy))
				.toList();
		return new AdopcionView(FinanciadorView.de(copia), planesDeLaCopia, creado);
	}

	/**
	 * <b>Cualquier</b> choque de unique al insertar la copia se resuelve releyendo, no solo el de
	 * {@code uk_financiador_catalogo_vigente}: dos copias simultaneas del mismo origen comparten
	 * tambien codigo y nombre, y MySQL informa el PRIMER indice que choca —en la practica
	 * {@code uk_financiador_codigo_vigente}—. Por eso no se decide por el nombre del indice si hubo
	 * carrera: se decide releyendo. El nombre solo elige el 409 para cuando no la hubo.
	 */
	private static ChoqueAlAdoptar traducir(DataIntegrityViolationException choque, String codigo) {
		RuntimeException traducido;
		if (ChoquesDeUnique.contiene(choque, "_nombre_")) {
			traducido = new FinanciadorNombreTakenException();
		} else if (ChoquesDeUnique.contiene(choque, "_cuit_")) {
			traducido = new FinanciadorCuitTakenException();
		} else if (ChoquesDeUnique.contiene(choque, "_codigo_")) {
			traducido = new FinanciadorCodigoTakenException(codigo);
		} else {
			// Incluye uk_financiador_catalogo_vigente sin copia que releer, que no deberia pasar:
			// mejor un 500 honesto que un 409 inventado.
			traducido = choque;
		}
		return new ChoqueAlAdoptar(traducido);
	}

	/** El insert de la copia choco contra un unique; la decision se toma despues del rollback. */
	private static final class ChoqueAlAdoptar extends RuntimeException {

		private final transient RuntimeException traducido;

		ChoqueAlAdoptar(RuntimeException traducido) {
			super(traducido);
			this.traducido = traducido;
		}
	}
}
