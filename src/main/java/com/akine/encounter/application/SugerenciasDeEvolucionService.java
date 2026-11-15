package com.akine.encounter.application;

import com.akine.clinical.spi.AccesoClinicoConcedido;
import com.akine.clinical.spi.AccesoClinicoDirectory;
import com.akine.clinical.spi.ActorClinico;
import com.akine.clinical.spi.CasoDetalleSnapshot;
import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.clinical.spi.PlanVigenteSnapshot;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.SugerenciaEvolucion;
import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo;
import com.akine.encounter.domain.evolucion.sugerencias.AjustesDeSugerencias;
import com.akine.encounter.domain.evolucion.sugerencias.ElectorDeHallazgos;
import com.akine.encounter.domain.evolucion.sugerencias.EntradaDelPrompt;
import com.akine.encounter.domain.evolucion.sugerencias.HallazgoConEvidencia;
import com.akine.encounter.domain.evolucion.sugerencias.PromptDeSugerencias;
import com.akine.encounter.domain.evolucion.sugerencias.RecursosDeSugerencias;
import com.akine.encounter.domain.evolucion.sugerencias.ResultadoDeValidacion;
import com.akine.encounter.domain.evolucion.sugerencias.SugerenciaPropuesta;
import com.akine.encounter.domain.evolucion.sugerencias.TextosDeSesion;
import com.akine.encounter.domain.evolucion.sugerencias.ValidadorDeSugerencias;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.EvolucionDeshabilitadaException;
import com.akine.encounter.domain.exception.GeneracionEnCursoException;
import com.akine.encounter.domain.exception.ModeloDeEvolucionNoDisponibleException;
import com.akine.encounter.domain.exception.ModeloDeEvolucionOcupadoException;
import com.akine.encounter.domain.exception.SugerenciaNoAccesibleException;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.SugerenciaEvolucionRepositoryPort;
import com.akine.encounter.spi.CitaGuardada;
import com.akine.knowledge.spi.BibliotecaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.ModeloOcupadoException;
import com.akine.platform.spi.modelo.OpcionesGeneracion;
import com.akine.platform.spi.texto.DetectorDeInstrucciones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sugerencias de revision del tratamiento sobre un Caso Clinico, respaldadas por la biblioteca del
 * centro (M31 fase 2, {@code docs/diseno/AKINE-analizador-de-evolucion-fase2.md}).
 *
 * <h2>Tres tramos, y la llamada al modelo fuera de toda transaccion (diseno Â§4.2)</h2>
 *
 * <ol>
 *   <li>Transaccion corta: el calculo de la fase 1, el permiso {@code evolucion:sugerir}, la puerta
 *       clinica y los textos de las ultimas sesiones.</li>
 *   <li>Sin transaccion: eleccion de hallazgos, una busqueda en la biblioteca por hallazgo, el modelo
 *       y el validador. Retener una conexion de Hikari durante la generacion es la traba que midio
 *       G-10.</li>
 *   <li>Transaccion corta: se guardan las sugerencias aceptadas y se audita, tambien el error.</li>
 * </ol>
 *
 * <p>Las transacciones se abren con {@link TransactionTemplate} y no con metodos
 * {@code @Transactional} de esta misma clase: la autoinvocacion no pasa por el proxy.
 *
 * <p>Con cualquiera de los tres interruptores apagado, todo responde 404 antes de mirar permisos.
 */
@Service
public class SugerenciasDeEvolucionService {

	private static final Logger log = LoggerFactory.getLogger(SugerenciasDeEvolucionService.class);

	/** Nombre del bean del modelo ({@code SugerenciasDeEvolucionConfig.MODELO}). */
	static final String MODELO = "modeloEvolucion";
	static final String TEXTO_OMITIDO = "TEXTO_OMITIDO";
	static final int TEXTOS_DE_SESIONES = 3;
	/** Publica para el arnes de evaluacion, que llama al modelo igual que el servicio. */
	public static final OpcionesGeneracion OPCIONES = new OpcionesGeneracion(new BigDecimal("0.2"), 1500, true,
			8192);
	static final String ERROR = "ERROR";
	private static final String OPERACION = "Sugerencias de evolucion del caso";

	private final AjustesDeSugerencias ajustes;
	private final AnalizadorDelCaso analizador;
	private final AccesoClinicoDirectory accesoClinico;
	private final PermissionGuard permissionGuard;
	private final ConsultorioDirectory consultorios;
	private final CasoDirectory casos;
	private final HistoriaClinicaDirectory historias;
	private final SesionRepositoryPort sesiones;
	private final SugerenciaEvolucionRepositoryPort sugerencias;
	private final BibliotecaDirectory biblioteca;
	private final ObjectProvider<ModeloDeLenguaje> modelo;
	private final CandadoPorCuenta candado;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final DetectorDeInstrucciones detector = DetectorDeInstrucciones.porDefecto();
	private final ValidadorDeSugerencias validador;
	private final Clock clock;

	@Autowired
	@SuppressWarnings("checkstyle:ParameterNumber")
	public SugerenciasDeEvolucionService(
			AjustesDeSugerencias ajustes,
			AnalizadorDelCaso analizador,
			AccesoClinicoDirectory accesoClinico,
			PermissionGuard permissionGuard,
			ConsultorioDirectory consultorios,
			CasoDirectory casos,
			HistoriaClinicaDirectory historias,
			SesionRepositoryPort sesiones,
			SugerenciaEvolucionRepositoryPort sugerencias,
			BibliotecaDirectory biblioteca,
			@Qualifier(MODELO) ObjectProvider<ModeloDeLenguaje> modelo,
			CandadoPorCuenta candado,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager) {
		this.ajustes = ajustes;
		this.analizador = analizador;
		this.accesoClinico = accesoClinico;
		this.permissionGuard = permissionGuard;
		this.consultorios = consultorios;
		this.casos = casos;
		this.historias = historias;
		this.sesiones = sesiones;
		this.sugerencias = sugerencias;
		this.biblioteca = biblioteca;
		this.modelo = modelo;
		this.candado = candado;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
		this.validador = new ValidadorDeSugerencias(RecursosDeSugerencias.formasPrescriptivas(), detector);
		this.clock = Clock.systemUTC();
	}

	// =============================================================================================
	// Generar

	/**
	 * Pide sugerencias para el caso. No es {@code @Transactional}: abre dos transacciones cortas y
	 * llama al modelo entre las dos, sin conexion tomada.
	 *
	 * @throws GeneracionEnCursoException si la cuenta ya tiene un pedido corriendo (429)
	 * @throws ModeloDeEvolucionNoDisponibleException si el modelo fallo; queda auditado con ERROR (503)
	 */
	public SugerenciasView generar(OperatingActor actor, long consultorioId, long casoId, String justificacion) {
		exigirHabilitadas();
		long organizationId = organizacionDe(actor);
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Las sugerencias llaman al modelo: no pueden correr dentro de una"
					+ " transaccion (diseno fase 2, Â§4.2)");
		}
		if (!candado.tomar(actor.accountId())) {
			throw new GeneracionEnCursoException();
		}
		try {
			Lectura lectura = transaccion.execute(estado -> leer(actor, organizationId, consultorioId, casoId,
					justificacion));
			return generarSinTransaccion(actor, organizationId, consultorioId, casoId, lectura);
		}
		finally {
			candado.soltar(actor.accountId());
		}
	}

	/** Lo que el primer tramo deja para los otros dos. Sin entidades: la sesion JPA ya cerro. */
	private record Lectura(CasoAnalizado calculo, AccesoClinicoConcedido acceso, List<TextosDeSesion> textos,
			List<String> advertencias) {
	}

	private Lectura leer(OperatingActor actor, long organizationId, long consultorioId, long casoId,
			String justificacion) {
		CasoAnalizado calculo = analizador.analizar(organizationId, consultorioId, casoId);
		exigirPermiso(actor, organizationId, consultorioId);
		AccesoClinicoConcedido acceso = accesoClinico.exigirLectura(
				new ActorClinico(actor.accountId(), actor.platformAdmin(), organizationId, consultorioId),
				calculo.historia().personaId(), justificacion, OPERACION);
		List<Sesion> cerradas = calculo.cerradas();
		List<TextosDeSesion> textos = cerradas.subList(Math.max(0, cerradas.size() - TEXTOS_DE_SESIONES),
						cerradas.size()).stream()
				.map(s -> new TextosDeSesion(s.getRespuestaTratamiento(), s.getLimitacionFuncional(),
						s.getNotaDeCierre()))
				.toList();
		return new Lectura(calculo, acceso, textos,
				EvolucionDelCasoService.advertencias(sesiones, organizationId, calculo));
	}

	private SugerenciasView generarSinTransaccion(OperatingActor actor, long organizationId, long consultorioId,
			long casoId, Lectura lectura) {
		UUID generacionId = UUID.randomUUID();
		CasoAnalizado calculo = lectura.calculo();
		List<Hallazgo> elegidos = ElectorDeHallazgos.elegir(calculo.analisis().hallazgos());
		if (elegidos.isEmpty()) {
			return cerrarSinModelo(actor, organizationId, consultorioId, casoId, lectura, generacionId,
					SugerenciasView.Resultado.SIN_HALLAZGOS_ELEGIBLES, elegidos);
		}
		List<HallazgoConEvidencia> evidencia = buscarEvidencia(organizationId, calculo.caso(), elegidos);
		if (evidencia.isEmpty()) {
			return cerrarSinModelo(actor, organizationId, consultorioId, casoId, lectura, generacionId,
					SugerenciasView.Resultado.SIN_MATERIAL, elegidos);
		}

		PromptDeSugerencias prompt = PromptDeSugerencias.armar(entrada(calculo), evidencia, lectura.textos(),
				detector);
		List<CodigoDeHallazgo> enviados = prompt.enviados().stream().map(e -> e.hallazgo().codigo()).toList();
		String salida;
		try {
			salida = modelo.getObject().completarJson(prompt.mensajes(), prompt.esquemaJson(), OPCIONES,
					ajustes.plazo());
		}
		catch (ModeloOcupadoException e) {
			auditarError(actor, organizationId, consultorioId, casoId, lectura.acceso(), enviados);
			throw new ModeloDeEvolucionOcupadoException(e);
		}
		catch (ModeloNoDisponibleException e) {
			auditarError(actor, organizationId, consultorioId, casoId, lectura.acceso(), enviados);
			throw new ModeloDeEvolucionNoDisponibleException(e);
		}
		ResultadoDeValidacion validacion = validador.validar(salida, prompt);

		List<String> advertencias = new ArrayList<>(lectura.advertencias());
		if (prompt.huboTextoOmitido()) {
			advertencias.add(0, TEXTO_OMITIDO);
		}
		Map<CodigoDeHallazgo, List<Long>> sesionesPorHallazgo = elegidos.stream()
				.collect(Collectors.toMap(Hallazgo::codigo, Hallazgo::sesiones, (a, b) -> a));
		Instant ahora = Instant.now(clock);
		List<SugerenciaEvolucion> nuevas = validacion.aceptadas().stream()
				.map(s -> nueva(organizationId, calculo, generacionId, actor.accountId(), ahora, s,
						sesionesPorHallazgo.getOrDefault(s.hallazgo(), List.of())))
				.toList();

		List<SugerenciaEvolucion> guardadas = transaccion.execute(estado -> {
			List<SugerenciaEvolucion> filas = nuevas.isEmpty() ? List.of() : sugerencias.saveAll(nuevas);
			auditarGeneracion(actor, organizationId, consultorioId, casoId, lectura.acceso(),
					SugerenciasView.Resultado.SUGERENCIAS.name(), enviados, filas.size(), validacion);
			return filas;
		});
		log.info("Sugerencias de evolucion generadas: casoId={} hallazgos={} guardadas={} descartadas={}",
				casoId, enviados.size(), guardadas.size(), validacion.descartadas());
		return new SugerenciasView(generacionId, SugerenciasView.Resultado.SUGERENCIAS,
				guardadas.stream().map(SugerenciasView.Sugerencia::de).toList(), validacion.descartadas(),
				advertencias);
	}

	private SugerenciasView cerrarSinModelo(OperatingActor actor, long organizationId, long consultorioId,
			long casoId, Lectura lectura, UUID generacionId, SugerenciasView.Resultado resultado,
			List<Hallazgo> elegidos) {
		List<CodigoDeHallazgo> codigos = elegidos.stream().map(Hallazgo::codigo).toList();
		transaccion.executeWithoutResult(estado -> auditarGeneracion(actor, organizationId, consultorioId, casoId,
				lectura.acceso(), resultado.name(), codigos, 0, null));
		log.info("Sugerencias de evolucion sin modelo: casoId={} resultado={}", casoId, resultado);
		return new SugerenciasView(generacionId, resultado, List.of(), 0, lectura.advertencias());
	}

	/** Una busqueda por hallazgo (diseno Â§5.2), en {@link EvidenciaDeSugerencias}. */
	private List<HallazgoConEvidencia> buscarEvidencia(long organizationId, CasoDetalleSnapshot caso,
			List<Hallazgo> elegidos) {
		return EvidenciaDeSugerencias.buscar(biblioteca, organizationId, caso.diagnosticoPresuntivo(), elegidos);
	}

	private static EntradaDelPrompt entrada(CasoAnalizado calculo) {
		return new EntradaDelPrompt(calculo.caso().diagnosticoPresuntivo(), calculo.caso().objetivoTerapeutico(),
				calculo.plan().map(PlanVigenteSnapshot::objetivos).orElse(null), calculo.datos());
	}

	private SugerenciaEvolucion nueva(long organizationId, CasoAnalizado calculo, UUID generacionId, long cuentaId,
			Instant ahora, SugerenciaPropuesta propuesta, List<Long> sesionesDelHallazgo) {
		List<CitaGuardada> citas = propuesta.citas().stream()
				.map(c -> new CitaGuardada(c.pasaje().libroId(), c.pasaje().titulo(), c.pasaje().paginaDesde(),
						c.pasaje().paginaHasta(), c.pasaje().puntaje(), sha256(c.pasaje().texto())))
				.toList();
		return new SugerenciaEvolucion(organizationId, calculo.caso().id(), calculo.caso().historiaClinicaId(),
				generacionId, cuentaId, ahora, propuesta.hallazgo().name(), propuesta.texto(), propuesta.fundamento(),
				citas, sesionesDelHallazgo, ajustes.modelo());
	}

	static String sha256(String texto) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(texto.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("La JVM no tiene SHA-256", e);
		}
	}

	// =============================================================================================
	// Listar y responder

	/** El historial del caso, de la mas nueva a la mas vieja. Es lectura clinica: audita. */
	public Page<SugerenciasView.Sugerencia> listar(OperatingActor actor, long consultorioId, long casoId,
			String justificacion, Pageable pagina) {
		exigirHabilitadas();
		long organizationId = organizacionDe(actor);
		return transaccion.execute(estado -> {
			AccesoClinicoConcedido acceso = exigirAcceso(actor, organizationId, consultorioId, casoId,
					justificacion);
			Page<SugerenciaEvolucion> leidas = sugerencias.findDelCaso(organizationId, casoId, pagina);
			auditTrail.record(entrada(actor, organizationId, consultorioId,
					AuditEvents.SUGERENCIAS_DE_EVOLUCION_CONSULTADAS, EvolucionDelCasoService.ENTIDAD, casoId, null,
					Map.of("cantidad", String.valueOf(leidas.getNumberOfElements()), "via", acceso.via()),
					acceso.justificacion()));
			return leidas.map(SugerenciasView.Sugerencia::de);
		});
	}

	/**
	 * Acepta o descarta una sugerencia. Primero la version: si no coincide, 409
	 * {@code concurrent-modification}; despues el estado, 409 {@code sugerencia-ya-respondida}.
	 */
	public SugerenciasView.Sugerencia responder(OperatingActor actor, long consultorioId, long casoId,
			long sugerenciaId, SugerenciaEvolucion.Respuesta respuesta, String motivo, long version,
			String justificacion) {
		exigirHabilitadas();
		long organizationId = organizacionDe(actor);
		// La vista se arma despues del commit: antes del flush la entidad tiene la version vieja, y el
		// cliente que la reenviara comeria un 409.
		SugerenciaEvolucion respondida = transaccion.execute(estado -> {
			AccesoClinicoConcedido acceso = exigirAcceso(actor, organizationId, consultorioId, casoId,
					justificacion);
			SugerenciaEvolucion sugerencia = sugerencias.findById(organizationId, sugerenciaId)
					.filter(s -> s.getCasoId() == casoId)
					.orElseThrow(() -> new SugerenciaNoAccesibleException(sugerenciaId));
			if (sugerencia.getVersion() != version) {
				throw new OptimisticLockingFailureException("La sugerencia " + sugerenciaId
						+ " cambio desde que se leyo");
			}
			sugerencia.responder(respuesta, motivo, actor.accountId(), Instant.now(clock));
			SugerenciaEvolucion guardada = sugerencias.save(sugerencia);
			auditTrail.record(entrada(actor, organizationId, consultorioId,
					AuditEvents.SUGERENCIA_DE_EVOLUCION_RESPONDIDA, AuditEvents.ENTITY_SUGERENCIA_EVOLUCION,
					sugerenciaId, respuesta.name(), Map.of("casoId", String.valueOf(casoId), "via", acceso.via()),
					acceso.justificacion()));
			return guardada;
		});
		return SugerenciasView.Sugerencia.de(respondida);
	}

	// =============================================================================================
	// Puerta y auditoria

	private void exigirHabilitadas() {
		if (!ajustes.habilitadas()) {
			throw new EvolucionDeshabilitadaException();
		}
	}

	private static long organizacionDe(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("Las sugerencias de evolucion requieren un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private void exigirPermiso(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(actor.accountId(), PermissionCodes.EVOLUCION_SUGERIR,
				organizationId, consultorioId, null, Instant.now(clock)));
	}

	/** Sede y caso de la organizacion (si no, 404), permiso y puerta clinica, sin correr el analisis. */
	private AccesoClinicoConcedido exigirAcceso(OperatingActor actor, long organizationId, long consultorioId,
			long casoId, String justificacion) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
		CasoDetalleSnapshot caso = casos.detalle(organizationId, casoId)
				.orElseThrow(() -> new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));
		HistoriaClinicaSnapshot historia = historias.findPorId(organizationId, caso.historiaClinicaId())
				.orElseThrow(() -> new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));
		exigirPermiso(actor, organizationId, consultorioId);
		return accesoClinico.exigirLectura(
				new ActorClinico(actor.accountId(), actor.platformAdmin(), organizationId, consultorioId),
				historia.personaId(), justificacion, OPERACION);
	}

	private void auditarError(OperatingActor actor, long organizationId, long consultorioId, long casoId,
			AccesoClinicoConcedido acceso, List<CodigoDeHallazgo> enviados) {
		log.warn("El modelo de sugerencias no respondio: casoId={}", casoId);
		transaccion.executeWithoutResult(estado -> auditarGeneracion(actor, organizationId, consultorioId, casoId,
				acceso, ERROR, enviados, 0, null));
	}

	/** Sin texto clinico: resultado, codigos, cantidad y descartes por regla. */
	private void auditarGeneracion(OperatingActor actor, long organizationId, long consultorioId, long casoId,
			AccesoClinicoConcedido acceso, String resultado, List<CodigoDeHallazgo> codigos, int guardadas,
			ResultadoDeValidacion validacion) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("resultado", resultado);
		detalles.put("hallazgos", codigos.stream().map(Enum::name).collect(Collectors.joining(",")));
		detalles.put("sugerencias", String.valueOf(guardadas));
		detalles.put("descartes", validacion == null ? "" : new EnumMap<>(validacion.descartes()).entrySet().stream()
				.map(e -> e.getKey().name() + "=" + e.getValue()).collect(Collectors.joining(",")));
		detalles.put("via", acceso.via());
		auditTrail.record(entrada(actor, organizationId, consultorioId,
				AuditEvents.SUGERENCIAS_DE_EVOLUCION_GENERADAS, EvolucionDelCasoService.ENTIDAD, casoId, resultado,
				detalles, acceso.justificacion()));
	}

	private AuditEntry entrada(OperatingActor actor, long organizationId, long consultorioId, String evento,
			String entidad, long entidadId, String estadoNuevo, Map<String, String> detalles, String motivo) {
		return new AuditEntry(organizationId, consultorioId, actor.accountId(), evento, entidad, entidadId, null,
				estadoNuevo, detalles, motivo, AuditEvents.correlationId(), Instant.now(clock));
	}
}
