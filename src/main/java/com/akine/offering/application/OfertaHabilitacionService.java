package com.akine.offering.application;

import com.akine.offering.application.HabilitacionesView.EspacioHabilitadoView;
import com.akine.offering.application.HabilitacionesView.ProfesionalHabilitadoView;
import com.akine.offering.application.ValidacionDeOfertaView.MotivoDeRechazo;
import com.akine.offering.domain.Habilitacion;
import com.akine.offering.domain.OfertaEspacioHabilitado;
import com.akine.offering.domain.OfertaProfesionalHabilitado;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.PermissionCodes;
import com.akine.offering.domain.exception.ConsultorioNoAccesibleException;
import com.akine.offering.domain.exception.ConsultorioNoOperableException;
import com.akine.offering.domain.exception.HabilitacionNoAccesibleException;
import com.akine.offering.domain.exception.OfertaInactivaException;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaEspacioHabilitadoRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaProfesionalHabilitadoRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Quien puede prestar cada Oferta y donde puede prestarse (AKINE-02.07).
 *
 * <p>Cubre RF-M27-006, RF-M04-008 y RF-M05-007, y la mitad calculable de RF-M04-009 y RF-M05-008
 * —la que no necesita un Turno para existir—.
 *
 * <h2>Las tres reglas que gobiernan todo lo de abajo</h2>
 *
 * <ol>
 *   <li><b>Lista vacia significa TODOS.</b> Una oferta sin habilitaciones no esta prohibida para
 *       nadie: esta sin restringir. Es lo que hace que el alta de 02.06 —que a proposito no pide
 *       quince datos— produzca una oferta usable. En cuanto entra la primera habilitacion, la
 *       oferta pasa a estar restringida.</li>
 *   <li><b>Habilitacion no es permiso.</b> Nada de aca se consulta desde {@code PermissionGuard}
 *       ni otorga acceso. Un profesional habilitado sigue necesitando su membership para entrar;
 *       un {@code ORG_ADMIN} sin habilitacion administra la oferta y no la presta.</li>
 *   <li><b>Todo se calcula al leer.</b> La capacidad efectiva y la validez de cada habilitacion
 *       salen de mirar las filas en el momento de la consulta. Materializarlas obligaria a
 *       recalcular cuando cambia un espacio o se desvincula un colaborador —filas de otros
 *       modulos— y la primera vez que alguien olvide hacerlo la agenda sobrevende un box.</li>
 * </ol>
 *
 * <h2>Por que los reemplazos son de conjunto completo</h2>
 *
 * <p>{@link #reemplazarProfesionales} y {@link #reemplazarEspacios} reciben la lista entera y
 * hacen el diff del lado del servidor: lo que entra y no estaba se crea, lo que estaba y no entra
 * se da de baja, y lo que sigue no se toca —conservando su id, su vigencia y su version—.
 *
 * <p>Es lo que la pantalla necesita: una grilla de casillas se guarda entera. La alternativa
 * —endpoints de alta y baja de a uno— obligaria al cliente a diffear, y un cliente que diffea mal
 * produce bajas que nadie pidio. Ademas cada reemplazo toma la {@code expectedVersion} de la
 * OFERTA, que es lo que serializa a dos administradores editando la misma configuracion: sin eso,
 * el segundo en guardar borra en silencio lo que agrego el primero.
 */
@Service
public class OfertaHabilitacionService {

	private static final Logger log = LoggerFactory.getLogger(OfertaHabilitacionService.class);

	/** Motivo con el que se cierran las habilitaciones que salen de un reemplazo. */
	private static final String MOTIVO_REEMPLAZO =
			"Quitada al reconfigurar las habilitaciones de la oferta";

	private final OfertaRepositoryPort ofertas;
	private final OfertaProfesionalHabilitadoRepositoryPort profesionales;
	private final OfertaEspacioHabilitadoRepositoryPort espacios;
	private final ConsultorioDirectory consultorioDirectory;
	private final ConsultorioMembershipDirectory membershipDirectory;
	private final AccountContextDirectory accountContextDirectory;
	private final AccountIdentityDirectory identityDirectory;
	private final EspacioDirectory espacioDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public OfertaHabilitacionService(
			OfertaRepositoryPort ofertas,
			OfertaProfesionalHabilitadoRepositoryPort profesionales,
			OfertaEspacioHabilitadoRepositoryPort espacios,
			ConsultorioDirectory consultorioDirectory,
			ConsultorioMembershipDirectory membershipDirectory,
			AccountContextDirectory accountContextDirectory,
			AccountIdentityDirectory identityDirectory,
			EspacioDirectory espacioDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.ofertas = ofertas;
		this.profesionales = profesionales;
		this.espacios = espacios;
		this.consultorioDirectory = consultorioDirectory;
		this.membershipDirectory = membershipDirectory;
		this.accountContextDirectory = accountContextDirectory;
		this.identityDirectory = identityDirectory;
		this.espacioDirectory = espacioDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * La configuracion completa de una oferta: profesionales, espacios y capacidad efectiva.
	 *
	 * <p>Devuelve las habilitaciones <b>activas e inactivas</b>. La fila dada de baja se muestra
	 * con su motivo y no se esconde: esconderla dejaria al administrador sin entender por que la
	 * capacidad efectiva cambio sola.
	 */
	@Transactional(readOnly = true)
	public HabilitacionesView leer(
			OperatingActor actor, long organizationId, long consultorioId, long ofertaId) {

		exigirLectura(actor, organizationId, consultorioId);
		OfertaServicioConsultorio oferta = cargar(organizationId, consultorioId, ofertaId);
		return armarVista(oferta, oferta.getVersion(), organizationId, ofertaId);
	}

	/**
	 * Si esa combinacion puede prestarse, y todos los motivos por los que no.
	 *
	 * <p>Los dos parametros son opcionales: preguntar sólo por el profesional, sólo por el
	 * espacio, o por los dos. Preguntar por ninguno valida la oferta sola, que sigue siendo una
	 * pregunta util —¿esta oferta se puede usar hoy?—.
	 *
	 * <p><b>Devuelve todos los motivos que fallan, no el primero.</b> Si al profesional le falta
	 * habilitacion Y el espacio esta fuera de servicio, arreglar uno solo no alcanza, y decirlo de
	 * a uno obliga a dos vueltas.
	 */
	@Transactional(readOnly = true)
	public ValidacionDeOfertaView validar(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			Long membershipId,
			Long espacioId) {

		ConsultorioSnapshot sede = exigirLectura(actor, organizationId, consultorioId);
		OfertaServicioConsultorio oferta = cargar(organizationId, consultorioId, ofertaId);
		Instant ahora = Instant.now();
		LocalDate hoy = LocalDate.now(ZoneId.of(sede.timezone()));

		List<MotivoDeRechazo> motivos = new ArrayList<>();

		if (!oferta.isOperable()) {
			motivos.add(new MotivoDeRechazo(
					MotivoDeRechazo.OFERTA_INACTIVA,
					"La oferta esta dada de baja y no admite reservas nuevas."));
		} else if (!oferta.estaVigente(hoy)) {
			motivos.add(new MotivoDeRechazo(
					MotivoDeRechazo.OFERTA_FUERA_DE_VIGENCIA,
					"La oferta esta activa pero hoy cae fuera de su ventana de vigencia."));
		}

		List<OfertaEspacioHabilitado> filasEspacio =
				espacios.findAllByOrganizationIdAndOfertaId(organizationId, ofertaId);

		if (membershipId != null) {
			motivos.addAll(motivosDelProfesional(organizationId, ofertaId, membershipId, ahora));
		}
		if (espacioId != null) {
			motivos.addAll(motivosDelEspacio(
					oferta, filasEspacio, organizationId, espacioId, ahora));
		}

		return new ValidacionDeOfertaView(
				ofertaId,
				membershipId,
				espacioId,
				motivos.isEmpty(),
				capacidadEfectiva(oferta, filasEspacio, organizationId, ahora),
				List.copyOf(motivos));
	}

	// =================================================================================
	// Reemplazos
	// =================================================================================

	/** Reemplaza el conjunto de profesionales habilitados. Ver el javadoc de la clase. */
	@Transactional
	public HabilitacionesView reemplazarProfesionales(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			Set<Long> membershipIds,
			long expectedVersion) {

		OfertaServicioConsultorio oferta = exigirOfertaConfigurable(
				actor, organizationId, consultorioId, ofertaId, expectedVersion);

		Set<Long> pedidos = normalizar(membershipIds);
		// Cada membership tiene que existir en ESTE tenant y alcanzar ESTA sede. Sin esta
		// verificacion, un id de otra organizacion entraria como fila valida y la habilitacion
		// quedaria apuntando a alguien que este centro no puede ver siquiera.
		for (Long membershipId : pedidos) {
			exigirMembershipDeLaSede(organizationId, consultorioId, membershipId);
		}

		Instant ahora = Instant.now();
		List<OfertaProfesionalHabilitado> actuales =
				profesionales.findAllByOrganizationIdAndOfertaIdAndActive(
						organizationId, ofertaId, true);

		Set<Long> vigentes = new LinkedHashSet<>();
		for (OfertaProfesionalHabilitado fila : actuales) {
			vigentes.add(fila.getMembershipId());
			if (!pedidos.contains(fila.getMembershipId())) {
				fila.deactivate(ahora, MOTIVO_REEMPLAZO);
				profesionales.save(fila);
				auditar(AuditEvents.HABILITACION_PROFESIONAL_REVOKED, oferta, actor,
						Map.of("membershipId", String.valueOf(fila.getMembershipId())));
			}
		}

		for (Long membershipId : pedidos) {
			if (!vigentes.contains(membershipId)) {
				profesionales.save(new OfertaProfesionalHabilitado(
						organizationId, consultorioId, ofertaId, membershipId, ahora, null));
				auditar(AuditEvents.HABILITACION_PROFESIONAL_GRANTED, oferta, actor,
						Map.of("membershipId", String.valueOf(membershipId)));
			}
		}

		log.info("Habilitaciones de profesional reconfiguradas: ofertaId={} habilitados={}",
				ofertaId, pedidos.size());

		return vistaTrasReemplazo(oferta, organizationId, ofertaId);
	}

	/** Reemplaza el conjunto de espacios habilitados. Ver el javadoc de la clase. */
	@Transactional
	public HabilitacionesView reemplazarEspacios(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			Set<Long> espacioIds,
			long expectedVersion) {

		OfertaServicioConsultorio oferta = exigirOfertaConfigurable(
				actor, organizationId, consultorioId, ofertaId, expectedVersion);

		Set<Long> pedidos = normalizar(espacioIds);
		Instant ahora = Instant.now();
		for (Long espacioId : pedidos) {
			exigirEspacioDeLaSede(organizationId, consultorioId, espacioId, ahora);
		}

		List<OfertaEspacioHabilitado> actuales =
				espacios.findAllByOrganizationIdAndOfertaIdAndActive(organizationId, ofertaId, true);

		Set<Long> vigentes = new LinkedHashSet<>();
		for (OfertaEspacioHabilitado fila : actuales) {
			vigentes.add(fila.getEspacioId());
			if (!pedidos.contains(fila.getEspacioId())) {
				fila.deactivate(ahora, MOTIVO_REEMPLAZO);
				espacios.save(fila);
				auditar(AuditEvents.HABILITACION_ESPACIO_REVOKED, oferta, actor,
						Map.of("espacioId", String.valueOf(fila.getEspacioId())));
			}
		}

		for (Long espacioId : pedidos) {
			if (!vigentes.contains(espacioId)) {
				espacios.save(new OfertaEspacioHabilitado(
						organizationId, consultorioId, ofertaId, espacioId, ahora, null));
				auditar(AuditEvents.HABILITACION_ESPACIO_GRANTED, oferta, actor,
						Map.of("espacioId", String.valueOf(espacioId)));
			}
		}

		log.info("Habilitaciones de espacio reconfiguradas: ofertaId={} habilitados={}",
				ofertaId, pedidos.size());

		return vistaTrasReemplazo(oferta, organizationId, ofertaId);
	}

	/**
	 * Cuantas habilitaciones vigentes dejaria colgando desvincular a este profesional.
	 *
	 * <p>Lo consume la desvinculacion de colaboradores de 02.03 para <b>avisar</b>, no para
	 * bloquear: quien administra el centro puede desvincular a alguien igual, y lo que no puede
	 * es hacerlo sin enterarse de que once ofertas se quedan sin ese profesional.
	 */
	@Transactional(readOnly = true)
	public long habilitacionesVigentesDe(long organizationId, long membershipId) {
		return profesionales.countByOrganizationIdAndMembershipIdAndActive(
				organizationId, membershipId, true);
	}

	// =================================================================================
	// Calculo
	// =================================================================================

	/**
	 * {@code min(capacidad de la oferta, capacidad de los espacios habilitados y en servicio)}.
	 *
	 * <p><b>Sin espacios habilitados devuelve la de la oferta</b>, no cero: no hay ningun espacio
	 * concreto contra el cual acotarla todavia. Devolver cero convertiria "sin restringir" en "no
	 * entra nadie", que es exactamente al reves.
	 *
	 * <p>Los espacios fuera de servicio <b>no cuentan</b>: acotar por la capacidad de un box que
	 * hoy no se puede usar daria un numero que no describe ninguna realidad.
	 */
	private int capacidadEfectiva(
			OfertaServicioConsultorio oferta,
			List<OfertaEspacioHabilitado> filas,
			long organizationId,
			Instant ahora) {

		return snapshotsHabilitados(filas, organizationId, ahora).stream()
				.mapToInt(EspacioSnapshot::capacidad)
				.min()
				.stream()
				.map(minima -> Math.min(oferta.getCapacidad(), minima))
				.findFirst()
				.orElse(oferta.getCapacidad());
	}

	/** El nombre del espacio que fija la efectiva, o {@code null} si ninguno la acota. */
	private String espacioQueLimita(
			OfertaServicioConsultorio oferta,
			List<OfertaEspacioHabilitado> filas,
			long organizationId,
			Instant ahora) {

		return snapshotsHabilitados(filas, organizationId, ahora).stream()
				.filter(espacio -> espacio.capacidad() < oferta.getCapacidad())
				.min((uno, otro) -> Integer.compare(uno.capacidad(), otro.capacidad()))
				.map(EspacioSnapshot::name)
				.orElse(null);
	}

	private List<EspacioSnapshot> snapshotsHabilitados(
			List<OfertaEspacioHabilitado> filas, long organizationId, Instant ahora) {

		List<EspacioSnapshot> encontrados = new ArrayList<>();
		for (OfertaEspacioHabilitado fila : filas) {
			if (!fila.rigeEn(ahora)) {
				continue;
			}
			espacioDirectory.find(organizationId, fila.getEspacioId(), ahora)
					.filter(EspacioSnapshot::enServicio)
					.ifPresent(encontrados::add);
		}
		return encontrados;
	}

	private List<MotivoDeRechazo> motivosDelProfesional(
			long organizationId, long ofertaId, long membershipId, Instant ahora) {

		List<MotivoDeRechazo> motivos = new ArrayList<>();

		Optional<ConsultorioMembershipSnapshot> vinculo =
				membershipDirectory.find(organizationId, membershipId);
		if (vinculo.isEmpty() || !vinculo.get().validAt(ahora)) {
			motivos.add(new MotivoDeRechazo(
					MotivoDeRechazo.VINCULO_NO_VIGENTE,
					"El vinculo de ese profesional con el centro no esta vigente."));
		}

		List<OfertaProfesionalHabilitado> filas =
				profesionales.findAllByOrganizationIdAndOfertaId(organizationId, ofertaId);
		if (!restringida(filas)) {
			// Sin restringir: cualquier profesional con vinculo vigente puede prestarla.
			return motivos;
		}

		Optional<OfertaProfesionalHabilitado> suya = filas.stream()
				.filter(fila -> fila.getMembershipId() == membershipId)
				.filter(Habilitacion::isOperable)
				.findFirst();

		if (suya.isEmpty()) {
			motivos.add(new MotivoDeRechazo(
					MotivoDeRechazo.PROFESIONAL_NO_HABILITADO,
					"Esa oferta esta restringida y ese profesional no esta entre los "
							+ "habilitados."));
		} else if (!suya.get().rigeEn(ahora)) {
			motivos.add(new MotivoDeRechazo(
					MotivoDeRechazo.HABILITACION_FUERA_DE_VIGENCIA,
					"Ese profesional esta habilitado, pero su habilitacion no rige hoy."));
		}
		return motivos;
	}

	private List<MotivoDeRechazo> motivosDelEspacio(
			OfertaServicioConsultorio oferta,
			List<OfertaEspacioHabilitado> filas,
			long organizationId,
			long espacioId,
			Instant ahora) {

		List<MotivoDeRechazo> motivos = new ArrayList<>();

		Optional<EspacioSnapshot> espacio =
				espacioDirectory.find(organizationId, espacioId, ahora);
		if (espacio.isEmpty() || !espacio.get().enServicio()) {
			motivos.add(new MotivoDeRechazo(
					MotivoDeRechazo.ESPACIO_FUERA_DE_SERVICIO,
					"Ese espacio no esta en servicio."));
		} else if (espacio.get().capacidad() < oferta.getCapacidad()) {
			// RF-M04-009: la capacidad fisica manda sobre la comercial. Es un motivo y no un
			// rechazo duro porque la oferta puede usarse igual con menos gente; quien decide es
			// la agenda, y para eso necesita saberlo.
			motivos.add(new MotivoDeRechazo(
					MotivoDeRechazo.CAPACIDAD_INSUFICIENTE,
					"Ese espacio admite " + espacio.get().capacidad() + " personas y la oferta "
							+ "declara " + oferta.getCapacidad() + "."));
		}

		if (restringida(filas)) {
			boolean habilitado = filas.stream()
					.filter(Habilitacion::isOperable)
					.anyMatch(fila -> fila.getEspacioId() == espacioId);
			if (!habilitado) {
				motivos.add(new MotivoDeRechazo(
						MotivoDeRechazo.ESPACIO_NO_HABILITADO,
						"Esa oferta esta restringida y ese espacio no esta entre los "
								+ "habilitados."));
			}
		}
		return motivos;
	}

	/**
	 * {@code true} si hay al menos una habilitacion ACTIVA.
	 *
	 * <p>Las dadas de baja no cuentan: una oferta que quedo con todas sus habilitaciones cerradas
	 * vuelve a estar sin restringir, y eso es coherente —quitar la ultima restriccion es quitar la
	 * restriccion—. Es tambien el caso peligroso que {@code restringida} existe para publicar.
	 */
	private static boolean restringida(List<? extends Habilitacion> filas) {
		return filas.stream().anyMatch(Habilitacion::isOperable);
	}

	// =================================================================================
	// Vistas
	// =================================================================================

	private List<ProfesionalHabilitadoView> vistasDeProfesional(
			List<OfertaProfesionalHabilitado> filas, long organizationId, Instant ahora) {

		Map<Long, ConsultorioMembershipSnapshot> vinculos = new LinkedHashMap<>();
		for (OfertaProfesionalHabilitado fila : filas) {
			membershipDirectory.find(organizationId, fila.getMembershipId())
					.ifPresent(snapshot -> vinculos.put(fila.getMembershipId(), snapshot));
		}

		Collection<Long> cuentas = vinculos.values().stream()
				.map(ConsultorioMembershipSnapshot::accountId)
				.toList();
		Map<Long, AccountIdentity> identidades = cuentas.isEmpty()
				? Map.of()
				: identityDirectory.identidadesDe(cuentas);

		List<ProfesionalHabilitadoView> vistas = new ArrayList<>();
		for (OfertaProfesionalHabilitado fila : filas) {
			ConsultorioMembershipSnapshot vinculo = vinculos.get(fila.getMembershipId());
			AccountIdentity identidad =
					vinculo == null ? null : identidades.get(vinculo.accountId());
			vistas.add(new ProfesionalHabilitadoView(
					fila.getId(),
					fila.getMembershipId(),
					identidad == null ? null : identidad.nombreCompleto(),
					vinculo == null ? null : vinculo.roleCode(),
					fila.getValidFrom(),
					fila.getValidUntil(),
					fila.isOperable() ? "ACTIVO" : "INACTIVO",
					fila.rigeEn(ahora),
					vinculo != null && vinculo.validAt(ahora),
					fila.getDeletedAt(),
					fila.getDeactivationReason(),
					fila.getVersion()));
		}
		return List.copyOf(vistas);
	}

	private List<EspacioHabilitadoView> vistasDeEspacio(
			List<OfertaEspacioHabilitado> filas, long organizationId, Instant ahora) {

		List<EspacioHabilitadoView> vistas = new ArrayList<>();
		for (OfertaEspacioHabilitado fila : filas) {
			Optional<EspacioSnapshot> espacio =
					espacioDirectory.find(organizationId, fila.getEspacioId(), ahora);
			vistas.add(new EspacioHabilitadoView(
					fila.getId(),
					fila.getEspacioId(),
					espacio.map(EspacioSnapshot::name).orElse(null),
					espacio.map(EspacioSnapshot::tipo).orElse(null),
					espacio.map(EspacioSnapshot::capacidad).orElse(0),
					fila.getValidFrom(),
					fila.getValidUntil(),
					fila.isOperable() ? "ACTIVO" : "INACTIVO",
					fila.rigeEn(ahora),
					espacio.map(EspacioSnapshot::enServicio).orElse(false),
					fila.getDeletedAt(),
					fila.getDeactivationReason(),
					fila.getVersion()));
		}
		return List.copyOf(vistas);
	}

	/**
	 * La configuracion resultante de un reemplazo, sin volver a autorizar.
	 *
	 * <p><b>{@code ofertaVersion} es {@code leida + 1}, no {@code oferta.getVersion()}.</b> La
	 * oferta se cargo con {@code OPTIMISTIC_FORCE_INCREMENT}, que Hibernate aplica al cerrar la
	 * transaccion: aca adentro la entidad todavia dice la version leida. Devolver esa haria que el
	 * siguiente reemplazo, mandado con lo que el servidor acaba de responder, chocara siempre con un
	 * 409 que no le echa la culpa a nadie —la conducta que {@code cambiarEquipo} documenta en vez de
	 * corregir—. El {@code +1} es exacto y no una estimacion porque el reemplazo no ensucia ninguna
	 * columna de la oferta: el unico avance es el forzado, y es uno solo. Lo fijan los dos tests de
	 * {@code HabilitacionesVersionForzadaIT}: que avanza una vez, y que lo devuelto alcanza para
	 * encadenar el siguiente reemplazo sin releer. Si un reemplazo pasara a tocar la oferta, la
	 * version avanzaria dos veces y los dos se romperian juntos.
	 */
	private HabilitacionesView vistaTrasReemplazo(
			OfertaServicioConsultorio oferta, long organizationId, long ofertaId) {
		return armarVista(oferta, oferta.getVersion() + 1, organizationId, ofertaId);
	}

	private HabilitacionesView armarVista(
			OfertaServicioConsultorio oferta, long ofertaVersion, long organizationId, long ofertaId) {

		Instant ahora = Instant.now();
		List<OfertaProfesionalHabilitado> filasProfesional =
				profesionales.findAllByOrganizationIdAndOfertaId(organizationId, ofertaId);
		List<OfertaEspacioHabilitado> filasEspacio =
				espacios.findAllByOrganizationIdAndOfertaId(organizationId, ofertaId);

		return new HabilitacionesView(
				ofertaId,
				ofertaVersion,
				restringida(filasProfesional),
				restringida(filasEspacio),
				vistasDeProfesional(filasProfesional, organizationId, ahora),
				vistasDeEspacio(filasEspacio, organizationId, ahora),
				oferta.getCapacidad(),
				capacidadEfectiva(oferta, filasEspacio, organizationId, ahora),
				espacioQueLimita(oferta, filasEspacio, organizationId, ahora));
	}

	// =================================================================================
	// Autorizacion e invariantes
	// =================================================================================

	private OfertaServicioConsultorio exigirOfertaConfigurable(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			long expectedVersion) {

		exigirContextoDeLaSede(actor, organizationId, consultorioId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));

		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		if (!sede.active()) {
			throw new ConsultorioNoOperableException(sede.id());
		}

		// Por `findWithLockByIdAndOrganizationIdAndConsultorioId` y no por `cargar`: es lo que hace avanzar la version de la
		// oferta al cerrar la transaccion. Sin ese avance, un reemplazo no ensucia ninguna columna
		// de `oferta`, la version se queda quieta, y la comparacion de abajo nunca falla para el
		// segundo administrador que guarda. Ver el javadoc del puerto.
		OfertaServicioConsultorio oferta = ofertas
				.findWithLockByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
		if (!oferta.isOperable()) {
			// Configurar quien presta una oferta dada de baja no tiene sentido y ademas
			// reabriria por la ventana lo que la baja cerro por la puerta.
			throw new OfertaInactivaException(ofertaId, "configurar");
		}
		if (oferta.getVersion() != expectedVersion) {
			throw new org.springframework.dao.OptimisticLockingFailureException(
					"La oferta avanzo desde la version que el cliente creia estar editando");
		}
		return oferta;
	}

	private void exigirContextoDeLaSede(
			OperatingActor actor, long organizationId, long consultorioId) {

		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Configuracion de habilitaciones sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| actor.consultorioId() != consultorioId) {
			log.info("Configuracion de habilitaciones fuera del contexto: accountId={} "
					+ "organizationId={} consultorioId={}",
					actor.accountId(), organizationId, consultorioId);
			throw new ConsultorioNoAccesibleException(consultorioId);
		}
	}

	private ConsultorioSnapshot exigirLectura(
			OperatingActor actor, long organizationId, long consultorioId) {

		if (actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| !accountContextDirectory.hasActiveMembership(actor.accountId(), organizationId)) {
			log.info("Lectura de habilitaciones de organizacion ajena rechazada: accountId={} "
					+ "organizationId={}", actor.accountId(), organizationId);
			throw new ConsultorioNoAccesibleException(consultorioId);
		}
		return exigirSedeDelTenant(organizationId, consultorioId);
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private OfertaServicioConsultorio cargar(
			long organizationId, long consultorioId, long ofertaId) {

		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
	}

	/**
	 * La membership existe en este tenant y alcanza esta sede.
	 *
	 * <p><b>404 y no 403</b>, con el mismo criterio que el resto del modulo: un id de otra
	 * organizacion tiene que ser indistinguible de uno inexistente, o bastaria recorrer numeros
	 * para averiguar cuanta gente tiene cada centro del SaaS.
	 *
	 * <p>Se acepta la membership <b>de toda la organizacion</b> ({@code consultorioId} nulo) y la
	 * acotada a ESTA sede. Una acotada a otra sede no: esa persona no atiende aca.
	 */
	private void exigirMembershipDeLaSede(
			long organizationId, long consultorioId, long membershipId) {

		ConsultorioMembershipSnapshot vinculo = membershipDirectory
				.find(organizationId, membershipId)
				.orElseThrow(() -> new HabilitacionNoAccesibleException(membershipId, "profesional"));

		Long sedeDelVinculo = vinculo.consultorioId();
		if (sedeDelVinculo != null && sedeDelVinculo != consultorioId) {
			throw new HabilitacionNoAccesibleException(membershipId, "profesional");
		}
	}

	/**
	 * El espacio existe, es de este tenant y es de ESTA sede.
	 *
	 * <p>Un espacio no se muda entre sedes (M04), asi que habilitar el de otra sede para una
	 * oferta de esta seria configurar algo que no se puede usar nunca.
	 */
	private void exigirEspacioDeLaSede(
			long organizationId, long consultorioId, long espacioId, Instant ahora) {

		EspacioSnapshot espacio = espacioDirectory.find(organizationId, espacioId, ahora)
				.orElseThrow(() -> new HabilitacionNoAccesibleException(espacioId, "espacio"));

		if (espacio.consultorioId() != consultorioId) {
			throw new HabilitacionNoAccesibleException(espacioId, "espacio");
		}
	}

	private static Set<Long> normalizar(Set<Long> ids) {
		if (ids == null) {
			return Set.of();
		}
		Set<Long> limpio = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id != null) {
				limpio.add(id);
			}
		}
		return limpio;
	}

	private void auditar(
			String eventType,
			OfertaServicioConsultorio oferta,
			OperatingActor actor,
			Map<String, String> details) {

		auditTrail.record(new AuditEntry(
				oferta.getOrganizationId(),
				oferta.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_HABILITACION,
				oferta.getId(),
				null,
				null,
				details,
				null,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
