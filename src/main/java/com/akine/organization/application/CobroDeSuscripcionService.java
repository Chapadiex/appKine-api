package com.akine.organization.application;

import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.SubscriptionCobro;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.SubscriptionCobroRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.domain.port.SubscriptionTransitionRepositoryPort;
import com.akine.organization.spi.ZonaDeOrganizacion;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cobro mensual de la suscripcion, registrado a mano por la plataforma (19/11/2026,
 * docs/diseno/AKINE-cobro-mensual.md).
 *
 * <p>Registrar un cobro extiende {@code pagado_hasta} y deja una fila de historial. Una prueba pasa
 * a ACTIVA por la maquina de estados, con su transicion. Se bloquea la suscripcion, que ya es el
 * punto de serializacion de todo lo que la escribe: dos cobros simultaneos se encadenan en vez de
 * pisarse, y el {@code @Version} sigue protegiendo contra una escritura que no la bloqueo.
 */
@Service
public class CobroDeSuscripcionService {

	private final SubscriptionRepositoryPort subscriptionRepository;
	private final SubscriptionCobroRepositoryPort cobroRepository;
	private final SubscriptionTransitionRepositoryPort transitionRepository;
	private final OrganizationRepositoryPort organizationRepository;
	private final ZonaDeOrganizacion zonas;
	private final AuditTrail auditTrail;
	private final Clock clock;

	@Autowired
	public CobroDeSuscripcionService(
			SubscriptionRepositoryPort subscriptionRepository,
			SubscriptionCobroRepositoryPort cobroRepository,
			SubscriptionTransitionRepositoryPort transitionRepository,
			OrganizationRepositoryPort organizationRepository,
			ZonaDeOrganizacion zonas,
			AuditTrail auditTrail) {
		this(subscriptionRepository, cobroRepository, transitionRepository, organizationRepository, zonas,
				auditTrail, Clock.systemUTC());
	}

	CobroDeSuscripcionService(
			SubscriptionRepositoryPort subscriptionRepository,
			SubscriptionCobroRepositoryPort cobroRepository,
			SubscriptionTransitionRepositoryPort transitionRepository,
			OrganizationRepositoryPort organizationRepository,
			ZonaDeOrganizacion zonas,
			AuditTrail auditTrail,
			Clock clock) {
		this.subscriptionRepository = subscriptionRepository;
		this.cobroRepository = cobroRepository;
		this.transitionRepository = transitionRepository;
		this.organizationRepository = organizationRepository;
		this.zonas = zonas;
		this.auditTrail = auditTrail;
		this.clock = clock;
	}

	/**
	 * Registra un cobro de {@code meses} meses.
	 *
	 * @throws OrganizationNotFoundException si el centro no existe o esta dado de baja
	 * @throws com.akine.organization.domain.exception.CobroNoAdmitidoException si la suscripcion
	 *         esta SUSPENDIDA o CANCELADA guardada
	 */
	@Transactional
	public CobroView registrar(long organizationId, int meses, String nota, Long actorAccountId) {
		organizationRepository.findByIdAndActiveTrue(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		Subscription subscription = subscriptionRepository.findByOrganizationIdForUpdate(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		// A microsegundos, la precision de timestamptz: lo que se responde es lo que queda guardado.
		Instant ahora = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		SubscriptionStatus estadoAntes = subscription.getStatus();
		Instant pagadoAntes = subscription.getPagadoHasta();

		// Desde una prueba el ciclo arranca hoy: no tiene periodo pagado que conservar.
		SubscriptionCobro.Periodo periodo = SubscriptionCobro.periodo(
				pagadoAntes, ahora, meses, zonas.de(organizationId));
		subscription.registrarCobro(periodo.hasta());
		Subscription guardada = subscriptionRepository.save(subscription);

		if (estadoAntes != guardada.getStatus()) {
			transitionRepository.save(new SubscriptionTransition(
					organizationId, guardada.getId(), estadoAntes, guardada.getStatus(),
					null, null, "Primer cobro registrado por la plataforma", actorAccountId, ahora));
		}
		String notaLimpia = nota == null || nota.isBlank() ? null : nota.strip();
		SubscriptionCobro cobro = cobroRepository.save(new SubscriptionCobro(
				organizationId, guardada.getId(), periodo, meses, notaLimpia, actorAccountId, ahora));

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("cobroId", String.valueOf(cobro.getId()));
		detalles.put("meses", String.valueOf(meses));
		detalles.put("periodoDesde", periodo.desde().toString());
		detalles.put("periodoHasta", periodo.hasta().toString());
		detalles.put("estado", guardada.getStatus().name());
		auditTrail.record(new AuditEntry(
				organizationId, null, actorAccountId, AuditEvents.SUBSCRIPTION_COBRO_REGISTRADO,
				AuditEvents.ENTITY_SUBSCRIPTION, guardada.getId(),
				pagadoAntes == null ? null : pagadoAntes.toString(), periodo.hasta().toString(),
				detalles, null, AuditEvents.correlationId(), ahora));
		return CobroView.de(cobro);
	}

	/** Historial de cobros del centro, el mas nuevo primero. */
	@Transactional(readOnly = true)
	public List<CobroView> historial(long organizationId) {
		organizationRepository.findByIdAndActiveTrue(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		return cobroRepository.findAllByOrganizationIdOrderByRegistradoEnDescIdDesc(organizationId).stream()
				.map(CobroView::de)
				.toList();
	}

	/** Un cobro registrado, para responder. */
	public record CobroView(long id, Instant periodoDesde, Instant periodoHasta, int meses, String nota,
			Long registradoPor, Instant registradoEn) {

		static CobroView de(SubscriptionCobro cobro) {
			return new CobroView(cobro.getId(), cobro.getPeriodoDesde(), cobro.getPeriodoHasta(),
					cobro.getMeses(), cobro.getNota(), cobro.getRegistradoPor(), cobro.getRegistradoEn());
		}
	}
}
