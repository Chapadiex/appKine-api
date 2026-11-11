package com.akine.knowledge.api;

import com.akine.knowledge.api.dto.ConsultaEventoEspera;
import com.akine.knowledge.api.dto.ConsultaEventoError;
import com.akine.knowledge.api.dto.ConsultaEventoFin;
import com.akine.knowledge.api.dto.ConsultaEventoFuentes;
import com.akine.knowledge.api.dto.ConsultaEventoTexto;
import com.akine.knowledge.api.dto.ConsultaRequest;
import com.akine.knowledge.api.dto.FuenteDeConsultaResponse;
import com.akine.knowledge.application.ColaDeConsultas;
import com.akine.knowledge.application.ConsultaPedido;
import com.akine.knowledge.application.ConsultaService;
import com.akine.knowledge.application.PlanDeRespuesta;
import com.akine.knowledge.domain.exception.AsistenteNoDisponibleException;
import com.akine.knowledge.domain.exception.ConsultaOcupadaException;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.domain.port.OpcionesGeneracion;
import com.akine.platform.spi.problem.ProblemType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consulta a la biblioteca con el modelo de lenguaje local (RF-M30-012), con la respuesta en
 * streaming ({@code text/event-stream}).
 *
 * <p>Interruptores, tenant, permiso, cuerpo y lugar en la cola se resuelven en el hilo del pedido:
 * lo que falla ahi sale como {@code application/problem+json} sin abrir el stream. El trabajo corre
 * despues en un hilo virtual (la cola acota cuantos hay: 1 en curso y {@code max-en-espera}) y solo
 * recibe {@code orgId} y el pedido ya validado, nunca el contexto de seguridad.
 *
 * <p>Nada de la pregunta, el historial, los pasajes ni la respuesta va al log ni a la metrica: el
 * log lleva duracion, cantidad de pasajes y resultado.
 */
@RestController
@RequestMapping(
		path = "/api/v1/organizations/{orgId}/biblioteca/consultas",
		produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE,
				MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@Tag(name = "Biblioteca", description = "Libros PDF de la organizacion")
public class ConsultaController implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(ConsultaController.class);

	static final String SIN_MATERIAL = "No encontré material en la biblioteca para responder eso.";
	static final OpcionesGeneracion OPCIONES = new OpcionesGeneracion(new BigDecimal("0.2"), 400);
	static final Duration LIMITE = Duration.ofSeconds(90);
	/**
	 * Algo mas que el limite de una consulta. El emisor lo multiplica por la consulta en curso mas
	 * las que pueden esperar: el plazo del emisor corre desde que se abre el stream, y la ultima en
	 * la fila puede esperar a tres consultas de 90 s antes de empezar la suya.
	 */
	static final long TIMEOUT_EMISOR_MS = 95_000L;

	static final String EVENTO_ESPERA = "espera";
	static final String EVENTO_FUENTES = "fuentes";
	static final String EVENTO_TEXTO = "texto";
	static final String EVENTO_FIN = "fin";
	static final String EVENTO_ERROR = "error";

	private final ConsultaService consultas;
	private final ColaDeConsultas cola;
	private final ObjectProvider<ModeloDeLenguaje> modelo;
	private final KnowledgeApiActor apiActor;
	private final Map<String, Counter> contadores;
	private final ExecutorService trabajos = Executors.newVirtualThreadPerTaskExecutor();

	public ConsultaController(
			ConsultaService consultas,
			ColaDeConsultas cola,
			ObjectProvider<ModeloDeLenguaje> modelo,
			KnowledgeApiActor apiActor,
			MeterRegistry metricas) {
		this.consultas = consultas;
		this.cola = cola;
		this.modelo = modelo;
		this.apiActor = apiActor;
		this.contadores = Map.of(
				Resultado.CON_MATERIAL, contador(metricas, Resultado.CON_MATERIAL),
				Resultado.SIN_MATERIAL, contador(metricas, Resultado.SIN_MATERIAL),
				Resultado.ERROR, contador(metricas, Resultado.ERROR),
				Resultado.OCUPADA, contador(metricas, Resultado.OCUPADA),
				Resultado.CANCELADA, contador(metricas, Resultado.CANCELADA));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(operationId = "consultarBiblioteca", summary = "Preguntar a la biblioteca",
			description = """
					Responde la pregunta con un modelo de lenguaje local, usando solo los pasajes de la \
					biblioteca de la organizacion. Requiere `biblioteca:consultar` y los interruptores \
					`akine.biblioteca.enabled` y `akine.biblioteca.consulta.enabled`. Nada se guarda.

					La respuesta 200 es un stream `text/event-stream`. Cada evento lleva su nombre en \
					`event:` y un JSON en `data:`:

					- `espera` (`ConsultaEventoEspera`): la consulta esta en la cola; cada vez que cambia \
					la posicion.
					- `fuentes` (`ConsultaEventoFuentes`): una vez, antes del texto, solo con material.
					- `texto` (`ConsultaEventoTexto`): fragmentos de la respuesta, en orden.
					- `fin` (`ConsultaEventoFin`): la respuesta termino. Sin material, antes llega un \
					unico `texto` con el aviso fijo y el modelo no se llama.
					- `error` (`ConsultaEventoError`): el modelo fallo o se cumplieron los 90 s; el \
					texto parcial queda. `type` es `asistente-no-disponible`.

					Las lineas que empiezan con `:` son comentarios de latido y se ignoran.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Stream de eventos de la respuesta",
					content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
							schema = @Schema(type = "string"))),
			@ApiResponse(responseCode = "400", description = "Pregunta en blanco o de mas de 600 caracteres, "
					+ "historial de mas de 6 turnos, o un turno sin rol valido o con mas de 2000 caracteres",
					content = {
							@Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class)),
							@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class))}),
			@ApiResponse(responseCode = "403", description = "Sin `biblioteca:consultar` en la organizacion, "
					+ "o sin organizacion en contexto",
					content = {
							@Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class)),
							@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class))}),
			@ApiResponse(responseCode = "404", description = LibroController.P404_ORG,
					content = {
							@Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class)),
							@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class))}),
			@ApiResponse(responseCode = "409", description = "`biblioteca-deshabilitada`: la biblioteca o la "
					+ "consulta estan apagadas",
					content = {
							@Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class)),
							@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class))}),
			@ApiResponse(responseCode = "503", description = "`consulta-ocupada`: la cola de la instancia esta "
					+ "llena (1 en curso y 3 esperando); trae `Retry-After`",
					content = {
							@Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class)),
							@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = ProblemDetail.class))})})
	public SseEmitter consultar(@PathVariable long orgId, @RequestBody ConsultaRequest request) {
		ConsultaPedido pedido = request.aPedido();
		consultas.validar(apiActor.current(), orgId, pedido);
		ColaDeConsultas.Turno turno;
		try {
			turno = cola.reservar();
		} catch (ConsultaOcupadaException e) {
			contadores.get(Resultado.OCUPADA).increment();
			log.info("Consulta a la biblioteca rechazada: cola llena");
			throw e;
		}

		SseEmitter emitter = new SseEmitter(TIMEOUT_EMISOR_MS * (1 + cola.maxEnEspera()));
		AtomicBoolean cancelado = new AtomicBoolean();
		Runnable cortar = () -> {
			cancelado.set(true);
			turno.abandonar();
		};
		emitter.onCompletion(cortar);
		emitter.onError(error -> cortar.run());
		emitter.onTimeout(() -> {
			cortar.run();
			emitter.complete();
		});
		try {
			trabajos.execute(() -> atender(emitter, turno, orgId, pedido, cancelado));
		} catch (RejectedExecutionException e) {
			turno.close();
			throw new AsistenteNoDisponibleException();
		}
		return emitter;
	}

	/**
	 * El cliente cerro el stream: Tomcat lo avisa con un despacho de error, y sin este handler lo
	 * tomaria el catch-all de {@code GlobalExceptionHandler}, que lo registra como error con su traza
	 * y despues no puede escribir el problema sobre un {@code text/event-stream}. No es un error: el
	 * trabajo ya lo conto como cancelada. Va en el controller porque es del transporte de esta
	 * operacion, no una excepcion del modulo.
	 */
	@ExceptionHandler(AsyncRequestNotUsableException.class)
	void clienteDesconectado() {
		// Nada que responder: no hay a quien.
	}

	private void atender(
			SseEmitter emitter, ColaDeConsultas.Turno turno, long orgId, ConsultaPedido pedido,
			AtomicBoolean cancelado) {
		long inicio = System.nanoTime();
		String resultado = Resultado.CANCELADA;
		int pasajes = 0;
		try (turno) {
			if (!turno.esperar(
					posicion -> enviar(emitter, EVENTO_ESPERA, new ConsultaEventoEspera(posicion)),
					() -> latido(emitter))
					|| cancelado.get()) {
				return;
			}
			Instant limite = Instant.now().plus(LIMITE);
			PlanDeRespuesta plan = consultas.preparar(orgId, pedido, limite);
			String camino = plan.camino().name();
			if (plan.sinMaterial()) {
				enviar(emitter, EVENTO_TEXTO, new ConsultaEventoTexto(SIN_MATERIAL));
				enviar(emitter, EVENTO_FIN, new ConsultaEventoFin(true, camino));
				resultado = Resultado.SIN_MATERIAL;
			} else {
				pasajes = plan.fuentes().size();
				enviar(emitter, EVENTO_FUENTES, new ConsultaEventoFuentes(
						plan.fuentes().stream().map(FuenteDeConsultaResponse::de).toList()));
				Duration plazo = Duration.between(Instant.now(), limite);
				if (plazo.isNegative() || plazo.isZero()) {
					throw new AsistenteNoDisponibleException();
				}
				ModeloDeLenguaje llm = modelo.getIfAvailable();
				if (llm == null) {
					throw new AsistenteNoDisponibleException();
				}
				llm.generar(plan.mensajes(), OPCIONES, plazo,
						fragmento -> enviar(emitter, EVENTO_TEXTO, new ConsultaEventoTexto(fragmento)),
						cancelado::get);
				if (cancelado.get()) {
					return;
				}
				enviar(emitter, EVENTO_FIN, new ConsultaEventoFin(false, camino));
				resultado = Resultado.CON_MATERIAL;
			}
			emitter.complete();
		} catch (ClienteDesconectado e) {
			resultado = Resultado.CANCELADA;
		} catch (RuntimeException e) {
			if (cancelado.get()) {
				resultado = Resultado.CANCELADA;
			} else {
				resultado = Resultado.ERROR;
				if (!(e instanceof AsistenteNoDisponibleException)) {
					// Solo la clase: el mensaje o la traza podrian llevar texto de la consulta.
					log.warn("Consulta a la biblioteca fallida: {}", e.getClass().getName());
				}
				terminarConError(emitter);
			}
		} finally {
			contadores.get(resultado).increment();
			log.info("Consulta a la biblioteca terminada: resultado={} pasajes={} duracionMs={}",
					resultado, pasajes, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio));
		}
	}

	private static void terminarConError(SseEmitter emitter) {
		try {
			enviar(emitter, EVENTO_ERROR,
					new ConsultaEventoError(ProblemType.ASISTENTE_NO_DISPONIBLE.uri().toString()));
			emitter.complete();
		} catch (ClienteDesconectado | IllegalStateException ignorada) {
			// El cliente ya no esta: no hay a quien avisar.
		}
	}

	/**
	 * Un evento con su DTO en JSON. Si el cliente se fue o el emisor ya termino, lanza
	 * {@link ClienteDesconectado}: el modelo la propaga tal cual y el trabajo la toma como
	 * cancelacion, no como falla del asistente.
	 */
	private static void enviar(SseEmitter emitter, String evento, Object dato) {
		try {
			emitter.send(SseEmitter.event().name(evento).data(dato, MediaType.APPLICATION_JSON));
		} catch (IOException | IllegalStateException e) {
			throw new ClienteDesconectado();
		}
	}

	/** Comentario SSE mientras espera en la cola: si el cliente se fue, la escritura falla. */
	private static void latido(SseEmitter emitter) {
		try {
			emitter.send(SseEmitter.event().comment("en cola"));
		} catch (IOException | IllegalStateException e) {
			throw new ClienteDesconectado();
		}
	}

	private static Counter contador(MeterRegistry metricas, String resultado) {
		return Counter.builder("akine.consultas")
				.description("Consultas a la biblioteca por resultado")
				.tag("resultado", resultado)
				.register(metricas);
	}

	@Override
	public void destroy() {
		trabajos.shutdownNow();
	}

	/** Valores de la etiqueta {@code resultado} de {@code akine_consultas_total}. */
	static final class Resultado {
		static final String CON_MATERIAL = "con_material";
		static final String SIN_MATERIAL = "sin_material";
		static final String ERROR = "error";
		static final String OCUPADA = "ocupada";
		static final String CANCELADA = "cancelada";

		private Resultado() {
		}
	}

	/** El cliente cerro la conexion o el emisor ya termino. Sin traza: es flujo de control. */
	static final class ClienteDesconectado extends RuntimeException {
		ClienteDesconectado() {
			super(null, null, false, false);
		}
	}
}
