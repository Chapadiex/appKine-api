package com.akine.contracting.application;

import com.akine.contracting.application.ImportacionAranceles.Fila;
import com.akine.contracting.application.ImportacionAranceles.Modo;
import com.akine.contracting.application.ImportacionAranceles.MotivoRechazo;
import com.akine.contracting.application.ImportacionAranceles.Resultado;
import com.akine.contracting.application.ImportacionAranceles.ResultadoFila;
import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.ConvenioArancel;
import com.akine.contracting.domain.Vigencia;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import com.akine.contracting.domain.exception.ConvenioYaInactivoException;
import com.akine.contracting.domain.exception.OfertaNoAccesibleException;
import com.akine.contracting.domain.exception.OfertaSinObraSocialException;
import com.akine.contracting.domain.exception.PracticaNoHabilitadaEnOfertaException;
import com.akine.contracting.domain.exception.SedeNoAccesibleException;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioArancelRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioRepositoryPort;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Importacion masiva de aranceles de UN convenio, con vista previa (B-7, RF-M16-007).
 *
 * <h2>Dos operaciones, una sola evaluacion</h2>
 *
 * <p>{@link #previsualizar} y {@link #confirmar} corren exactamente el mismo {@link #evaluar}. La
 * diferencia es el momento: el preview evalua sin lock y sin escribir, y la confirmacion vuelve a
 * evaluar TODO bajo el lock de {@code convenio_lock}, porque entre el preview y el boton alguien
 * pudo cargar un arancel que ahora se pisa. Lo que vio el preview es una prediccion, no una reserva.
 *
 * <h2>Cada fila falla por lo mismo que fallaria su alta suelta</h2>
 *
 * <p>Practica visible para el tenant, importes que cuadran, vigencia contenida en la del convenio,
 * oferta asociable ({@link AsociacionDeOferta}, la misma regla del alta) y RN-M16-002 por grupo
 * (convenio, practica, oferta). A eso el lote le suma una regla que el alta no puede tener: <b>dos
 * filas del mismo lote tampoco se pueden pisar entre si</b>. Sin ella, la confirmacion insertaria
 * las dos —ninguna choca contra lo que ya estaba— y dejaria el solapamiento que el lock existe para
 * impedir.
 *
 * <h2>Todo o nada (CA-M16-007-04)</h2>
 *
 * <p>Si una sola fila no entra, la confirmacion lanza {@link ImportacionArancelesRechazadaException}
 * ANTES de insertar la primera. No hay modo parcial: el RF lo admite solo si se declara, y una
 * planilla del financiador aplicada a medias es peor que no aplicada —la mitad de las practicas con
 * el nomenclador nuevo y la otra mitad con el viejo—.
 *
 * <h2>Reintentos (CA-M16-007-05) sin {@code Idempotency-Key}</h2>
 *
 * <p>El modulo no usa {@code Idempotency-Key} en ninguna escritura, y la importacion no lo necesita:
 * es idempotente por invariante. Reconfirmar un lote ya aplicado choca contra sus propias filas como
 * {@code ARANCEL_SOLAPADO} y responde 409 sin escribir nada. Nunca duplica.
 */
@Service
public class ImportacionArancelesService {

	private static final Logger log = LoggerFactory.getLogger(ImportacionArancelesService.class);

	/** Tope del lote. Un nomenclador completo de kinesiologia ronda las 150 practicas. */
	public static final int MAX_FILAS = 500;

	private final ConvenioRepositoryPort convenios;
	private final ConvenioArancelRepositoryPort aranceles;
	private final ConvenioLockRepositoryPort locks;
	private final ConvenioLockIniciador lockIniciador;
	private final ConsultorioDirectory consultorios;
	private final CatalogoDirectory catalogo;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final OfertaDirectory ofertas;
	private final PracticasDeOfertaDirectory practicasDeOferta;

	@SuppressWarnings("java:S107")
	public ImportacionArancelesService(
			ConvenioRepositoryPort convenios,
			ConvenioArancelRepositoryPort aranceles,
			ConvenioLockRepositoryPort locks,
			ConvenioLockIniciador lockIniciador,
			ConsultorioDirectory consultorios,
			CatalogoDirectory catalogo,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			OfertaDirectory ofertas,
			PracticasDeOfertaDirectory practicasDeOferta) {

		this.convenios = convenios;
		this.aranceles = aranceles;
		this.locks = locks;
		this.lockIniciador = lockIniciador;
		this.consultorios = consultorios;
		this.catalogo = catalogo;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.ofertas = ofertas;
		this.practicasDeOferta = practicasDeOferta;
	}

	/**
	 * Vista previa: el desenlace de cada fila si el lote se confirmara ahora. No escribe nada ni
	 * toma el lock. Exige {@code convenio:manage}, igual que la confirmacion: es la primera mitad
	 * de una escritura, no una consulta del padron.
	 */
	@Transactional(readOnly = true)
	public Resultado previsualizar(
			OperatingActor actor, long consultorioId, long convenioId, List<Fila> filas) {

		long organizationId = autorizar(actor, consultorioId, "Previsualizar una importacion");
		exigirLote(filas);
		Convenio convenio = cargarConvenio(organizationId, consultorioId, convenioId);
		exigirConvenioOperable(convenio);

		List<Evaluada> evaluadas = evaluar(organizationId, consultorioId, convenio, filas);
		return new Resultado(Modo.PREVIEW, false,
				evaluadas.stream().map(Evaluada::resultado).toList());
	}

	/**
	 * Confirmacion todo o nada: revalida el lote entero bajo el lock y, si todas las filas
	 * entran, las inserta. Si una no entra, {@link ImportacionArancelesRechazadaException} y cero
	 * filas escritas.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Resultado confirmar(
			OperatingActor actor, long consultorioId, long convenioId, List<Fila> filas) {

		long organizationId = autorizar(actor, consultorioId, "Confirmar una importacion");
		exigirLote(filas);
		// 404 antes de crear la fila-lock: un convenio ajeno no deja rastro en convenio_lock.
		cargarConvenio(organizationId, consultorioId, convenioId);

		// EL ORDEN DE ESTAS LINEAS ES LA GARANTIA. Ver la cabecera de ConvenioService: fila-lock en
		// transaccion aparte, lock tomado ANTES de leer, y READ_COMMITTED para que la relectura vea
		// lo que commiteo quien tuvo el lock antes.
		lockIniciador.asegurar(organizationId, consultorioId);
		BloqueoDeConvenios.tomar(locks, organizationId, consultorioId);

		// El convenio se relee bajo el lock: su vigencia o su estado pudieron cambiar desde el
		// preview, y la edicion de un convenio serializa sobre el mismo lock.
		Convenio convenio = cargarConvenio(organizationId, consultorioId, convenioId);
		exigirConvenioOperable(convenio);

		List<Evaluada> evaluadas = evaluar(organizationId, consultorioId, convenio, filas);
		if (evaluadas.stream().anyMatch(e -> e.resultado().rechazada())) {
			List<ResultadoFila> resultados = evaluadas.stream().map(Evaluada::resultado).toList();
			log.info("Importacion de aranceles rechazada: convenioId={} filas={} rechazadas={}",
					convenioId, resultados.size(),
					resultados.stream().filter(ResultadoFila::rechazada).count());
			throw new ImportacionArancelesRechazadaException(resultados);
		}

		List<ResultadoFila> creadas = new ArrayList<>(evaluadas.size());
		for (Evaluada evaluada : evaluadas) {
			ConvenioArancel creado = aranceles.saveAndFlush(evaluada.candidato());
			auditarAlta(creado, actor);
			creadas.add(evaluada.resultado().creada(creado.getId()));
		}
		auditarLote(convenio, actor, evaluadas);

		log.info("Importacion de aranceles aplicada: convenioId={} filas={}",
				convenioId, creadas.size());
		return new Resultado(Modo.CONFIRMAR, true, creadas);
	}

	// =================================================================================
	// Evaluacion
	// =================================================================================

	/**
	 * Una fila evaluada y, si paso las validaciones propias, el arancel que se insertaria.
	 * {@code candidato} existe tambien en una fila rechazada por solapamiento: hace falta para
	 * comparar las filas siguientes contra ella.
	 */
	private record Evaluada(ResultadoFila resultado, ConvenioArancel candidato) {
	}

	/** El motivo de rechazo de una fila, como control de flujo de {@link #evaluarFila}. */
	private static final class Rechazo extends RuntimeException {

		private final transient MotivoRechazo motivo;

		Rechazo(MotivoRechazo motivo, String detalle) {
			super(detalle, null, false, false);
			this.motivo = motivo;
		}
	}

	/**
	 * Evalua el lote en orden. Las lecturas se hacen una vez por lote: los aranceles activos del
	 * convenio, y el catalogo y las ofertas memorizados por id.
	 */
	private List<Evaluada> evaluar(
			long organizationId, long consultorioId, Convenio convenio, List<Fila> filas) {

		List<ConvenioArancel> vigentes = aranceles
				.findAllByConvenio(organizationId, convenio.getId()).stream()
				.filter(ConvenioArancel::isActive)
				.toList();
		Contexto contexto = new Contexto(organizationId, consultorioId, convenio, vigentes);

		List<Evaluada> evaluadas = new ArrayList<>(filas.size());
		for (int i = 0; i < filas.size(); i++) {
			evaluadas.add(evaluarFila(contexto, i + 1, filas.get(i), evaluadas));
		}
		return evaluadas;
	}

	private Evaluada evaluarFila(Contexto contexto, int numero, Fila fila, List<Evaluada> previas) {
		Long practicaId = null;
		Long ofertaId = fila == null ? null : fila.ofertaId();
		ConvenioArancel candidato;
		try {
			if (fila == null) {
				throw new Rechazo(MotivoRechazo.DATOS_INVALIDOS, "La fila esta vacia");
			}
			practicaId = contexto.resolverPractica(fila);
			candidato = construir(contexto, practicaId, fila);
			if (ofertaId != null) {
				contexto.exigirOfertaAsociable(ofertaId, practicaId);
			}
		} catch (Rechazo rechazo) {
			return new Evaluada(ResultadoFila.rechazada(
					numero, practicaId, ofertaId, rechazo.motivo, rechazo.getMessage()), null);
		}

		Optional<ConvenioArancel> choque = contexto.vigentes().stream()
				.filter(existente -> Objects.equals(
						existente.getPracticaId(), candidato.getPracticaId()))
				.filter(existente -> existente.esDelGrupo(ofertaId))
				.filter(existente -> existente.vigencia().seSolapaCon(candidato.vigencia()))
				.findFirst();
		if (choque.isPresent()) {
			return new Evaluada(ResultadoFila.rechazada(numero, practicaId, ofertaId,
					MotivoRechazo.ARANCEL_SOLAPADO,
					"Se pisa con el arancel vigente " + choque.get().getId() + " ("
							+ choque.get().vigencia() + ")")
					.conArancelExistente(choque.get().getId()), candidato);
		}

		for (Evaluada previa : previas) {
			ConvenioArancel otro = previa.candidato();
			if (otro != null
					&& Objects.equals(otro.getPracticaId(), candidato.getPracticaId())
					&& Objects.equals(otro.getOfertaId(), ofertaId)
					&& otro.vigencia().seSolapaCon(candidato.vigencia())) {
				return new Evaluada(ResultadoFila.rechazada(numero, practicaId, ofertaId,
						MotivoRechazo.ARANCEL_SOLAPADO,
						"Se pisa con la fila " + previa.resultado().fila() + " del mismo lote ("
								+ otro.vigencia() + ")")
						.conFilaEnConflicto(previa.resultado().fila()), candidato);
			}
		}

		return new Evaluada(ResultadoFila.alta(numero, practicaId, ofertaId), candidato);
	}

	/**
	 * El arancel que se insertaria. La entidad valida importes y vigencia —los mismos mensajes que
	 * el alta unitaria— y aca se suma lo que en el alta valida Bean Validation: los 2 decimales.
	 */
	private static ConvenioArancel construir(Contexto contexto, long practicaId, Fila fila) {
		exigirDosDecimales(fila.importeTotal(), "El importe total");
		exigirDosDecimales(fila.importeFinanciador(), "La parte del financiador");
		exigirDosDecimales(fila.coseguro(), "El coseguro");

		ConvenioArancel candidato;
		try {
			candidato = new ConvenioArancel(
					contexto.organizationId(),
					contexto.consultorioId(),
					contexto.convenio().getId(),
					practicaId,
					fila.ofertaId(),
					fila.importeTotal(),
					fila.importeFinanciador(),
					fila.coseguro(),
					contexto.convenio().getMoneda(),
					fila.vigenciaDesde(),
					fila.vigenciaHasta());
		} catch (IllegalArgumentException invalida) {
			throw new Rechazo(MotivoRechazo.DATOS_INVALIDOS, invalida.getMessage());
		}

		Vigencia delConvenio = contexto.convenio().vigencia();
		if (!candidato.vigencia().estaContenidaEn(delConvenio)) {
			throw new Rechazo(MotivoRechazo.DATOS_INVALIDOS,
					"La vigencia del arancel (" + candidato.vigencia() + ") tiene que estar "
							+ "contenida en la del convenio (" + delConvenio + ")");
		}
		return candidato;
	}

	private static void exigirDosDecimales(BigDecimal importe, String cual) {
		if (importe == null) {
			return; // La entidad lo rechaza con su propio mensaje.
		}
		BigDecimal normalizado = importe.stripTrailingZeros();
		if (normalizado.scale() > 2 || normalizado.precision() - normalizado.scale() > 10) {
			throw new Rechazo(MotivoRechazo.DATOS_INVALIDOS,
					cual + " admite hasta 10 enteros y 2 decimales: " + importe.toPlainString());
		}
	}

	/** Las lecturas de un lote, memorizadas: una planilla repite practicas y ofertas. */
	private final class Contexto {

		private final long organizationId;
		private final long consultorioId;
		private final Convenio convenio;
		private final List<ConvenioArancel> vigentes;
		private final Instant ahora = Instant.now();
		private final Map<Long, Optional<CatalogoSnapshot>> practicasPorId = new HashMap<>();
		private Map<String, List<CatalogoSnapshot>> practicasPorCodigo;

		Contexto(long organizationId, long consultorioId, Convenio convenio,
				List<ConvenioArancel> vigentes) {

			this.organizationId = organizationId;
			this.consultorioId = consultorioId;
			this.convenio = convenio;
			this.vigentes = vigentes;
		}

		long organizationId() {
			return organizationId;
		}

		long consultorioId() {
			return consultorioId;
		}

		Convenio convenio() {
			return convenio;
		}

		List<ConvenioArancel> vigentes() {
			return vigentes;
		}

		/**
		 * Por id: visible para el tenant, activa o no —como el alta unitaria—. Por codigo: entre
		 * las practicas ELEGIBLES hoy (globales y propias, vigentes), y tiene que resolver a una
		 * sola: un codigo propio que repite uno global es ambiguo y se rechaza en vez de adivinar.
		 */
		long resolverPractica(Fila fila) {
			String codigo = fila.codigoPractica() == null ? null : fila.codigoPractica().strip();
			boolean hayCodigo = codigo != null && !codigo.isEmpty();

			if (fila.practicaId() != null) {
				CatalogoSnapshot practica = practicasPorId
						.computeIfAbsent(fila.practicaId(),
								id -> catalogo.findPractica(organizationId, id, ahora))
						.orElseThrow(() -> new Rechazo(MotivoRechazo.PRACTICA_NO_ACCESIBLE,
								"La practica " + fila.practicaId() + " no existe"));
				if (hayCodigo && !codigo.equalsIgnoreCase(practica.codigo())) {
					throw new Rechazo(MotivoRechazo.DATOS_INVALIDOS,
							"El codigo " + codigo + " no es el de la practica " + practica.id()
									+ " (" + practica.codigo() + ")");
				}
				return practica.id();
			}
			if (!hayCodigo) {
				throw new Rechazo(MotivoRechazo.DATOS_INVALIDOS,
						"La fila necesita practicaId o codigoPractica");
			}

			if (practicasPorCodigo == null) {
				practicasPorCodigo = new HashMap<>();
				for (CatalogoSnapshot p : catalogo.practicasVigentes(organizationId, ahora)) {
					if (p.codigo() != null) {
						practicasPorCodigo
								.computeIfAbsent(clave(p.codigo()), k -> new ArrayList<>())
								.add(p);
					}
				}
			}
			List<CatalogoSnapshot> candidatas =
					practicasPorCodigo.getOrDefault(clave(codigo), List.of());
			if (candidatas.isEmpty()) {
				throw new Rechazo(MotivoRechazo.PRACTICA_NO_ACCESIBLE,
						"No hay una practica vigente con el codigo " + codigo);
			}
			if (candidatas.size() > 1) {
				throw new Rechazo(MotivoRechazo.PRACTICA_NO_ACCESIBLE,
						"El codigo " + codigo + " corresponde a " + candidatas.size()
								+ " practicas: indicala por practicaId");
			}
			return candidatas.get(0).id();
		}

		void exigirOfertaAsociable(long ofertaId, long practicaId) {
			try {
				AsociacionDeOferta.exigir(ofertas, practicasDeOferta, organizationId,
						consultorioId, ofertaId, practicaId);
			} catch (OfertaNoAccesibleException e) {
				throw new Rechazo(MotivoRechazo.OFERTA_NO_ACCESIBLE,
						"La oferta " + ofertaId + " no existe en esta sede");
			} catch (OfertaSinObraSocialException e) {
				throw new Rechazo(MotivoRechazo.OFERTA_SIN_OBRA_SOCIAL,
						"La oferta " + ofertaId + " no admite obra social");
			} catch (PracticaNoHabilitadaEnOfertaException e) {
				throw new Rechazo(MotivoRechazo.PRACTICA_NO_HABILITADA_EN_OFERTA,
						"La oferta " + ofertaId + " no declara la practica " + practicaId);
			}
		}

		private static String clave(String codigo) {
			return codigo.strip().toUpperCase(Locale.ROOT);
		}
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	private long autorizar(OperatingActor actor, long consultorioId, String operacion) {
		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, operacion);
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new SedeNoAccesibleException(consultorioId));
		AutorizacionDeCatalogo.exigirGestionDeLaSede(
				permissionGuard, actor, consultorioId, operacion);
		return organizationId;
	}

	private static void exigirLote(List<Fila> filas) {
		if (filas == null || filas.isEmpty()) {
			throw new IllegalArgumentException("La importacion necesita al menos una fila");
		}
		if (filas.size() > MAX_FILAS) {
			throw new IllegalArgumentException(
					"La importacion admite hasta " + MAX_FILAS + " filas por lote");
		}
	}

	private Convenio cargarConvenio(long organizationId, long consultorioId, long convenioId) {
		return convenios.findByIdAndScope(convenioId, organizationId, consultorioId)
				.orElseThrow(() -> new ConvenioNotAccessibleException(convenioId));
	}

	private static void exigirConvenioOperable(Convenio convenio) {
		if (!convenio.isOperable()) {
			throw new ConvenioYaInactivoException(convenio.getId(), "importar aranceles");
		}
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	/** El mismo {@code ARANCEL_CREATED} del alta unitaria, con el origen declarado. */
	private void auditarAlta(ConvenioArancel creado, OperatingActor actor) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("origen", "IMPORTACION");
		detalles.put("practicaId", String.valueOf(creado.getPracticaId()));
		if (creado.getOfertaId() != null) {
			detalles.put("ofertaId", String.valueOf(creado.getOfertaId()));
		}
		detalles.put("vigencia", creado.vigencia().toString());
		detalles.put("importeTotal", creado.getImporteTotal() + " " + creado.getMoneda());
		detalles.put("importeFinanciador", creado.getImporteFinanciador().toString());
		detalles.put("coseguro", creado.getCoseguro().toString());
		registrar(AuditEvents.ARANCEL_CREATED, AuditEvents.ENTITY_ARANCEL, creado.getId(),
				creado.getOrganizationId(), creado.getConsultorioId(), actor, "ACTIVO", detalles);
	}

	/** Un evento por lote, sobre el convenio: cuantas filas y que rango de fechas cubrieron. */
	private void auditarLote(Convenio convenio, OperatingActor actor, List<Evaluada> evaluadas) {
		List<Vigencia> vigencias = evaluadas.stream().map(e -> e.candidato().vigencia()).toList();
		LocalDate desde = vigencias.stream().map(Vigencia::desde)
				.min(Comparator.naturalOrder()).orElseThrow();
		boolean algunaAbierta = vigencias.stream().anyMatch(v -> v.hasta() == null);
		String hasta = algunaAbierta
				? "sin fin previsto"
				: vigencias.stream().map(Vigencia::hasta).max(Comparator.naturalOrder())
						.orElseThrow().toString();

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("cantidad", String.valueOf(evaluadas.size()));
		detalles.put("practicas", String.valueOf(
				evaluadas.stream().map(e -> e.candidato().getPracticaId()).distinct().count()));
		detalles.put("rango", desde + " a " + hasta);
		registrar(AuditEvents.ARANCELES_IMPORTADOS, AuditEvents.ENTITY_CONVENIO, convenio.getId(),
				convenio.getOrganizationId(), convenio.getConsultorioId(), actor, null, detalles);
	}

	@SuppressWarnings("java:S107")
	private void registrar(
			String eventType,
			String entityType,
			long entityId,
			long organizationId,
			long consultorioId,
			OperatingActor actor,
			String newState,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				eventType,
				entityType,
				entityId,
				null,
				newState,
				detalles,
				null,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
