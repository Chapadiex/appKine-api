package com.akine.resource.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.resource.domain.exception.BloqueInactivoException;
import com.akine.resource.domain.exception.BloqueNotAccessibleException;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.resource.domain.exception.ExcepcionInactivaException;
import com.akine.resource.domain.exception.ExcepcionNotAccessibleException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.exception.ProfesionalNotAccessibleException;
import com.akine.resource.domain.exception.VentanaDemasiadoAmpliaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Traduccion de las excepciones de dominio de la disponibilidad profesional (M05) a Problem
 * Details (RFC 7807).
 *
 * <h2>Por que no esta en {@code GlobalExceptionHandler}</h2>
 *
 * <p><b>No es duplicacion: es la unica ubicacion legal.</b> {@code GlobalExceptionHandler} vive
 * en {@code platform}, que es el modulo base. Mapear ahi estas excepciones exige importarlas, y
 * eso crea una dependencia {@code platform.api -> resource.domain} que cierra un ciclo —
 * {@code resource} ya depende de {@code platform.spi} para la auditoria y el contexto de tenant—.
 * {@code ModuleArchitectureTest} lo rechaza por partida doble.
 *
 * <h2>Que NO se declara aca, y por que</h2>
 *
 * <p><b>{@code ConsultorioNotAccessibleException} (404) y {@code ConsultorioNotOperableException}
 * (409) las mapea {@link EspacioProblemHandler}, del mismo modulo.</b> Declararlas otra vez aca
 * dejaria dos manejadores del mismo tipo en dos advices con el MISMO {@code @Order}, y cual gana
 * pasaria a depender del orden de registro de los beans: la clase de bug que se manifiesta al
 * renombrar un archivo. Los codigos que ese advice emite son exactamente los que M05 necesita, y
 * los operations de estos controllers los documentan.
 *
 * <p><b>{@code IllegalArgumentException} (400), {@code AccessDeniedException} (403) y
 * {@code OptimisticLockingFailureException} (409) ya estan en
 * {@code GlobalExceptionHandler}</b>, y ahi es donde corresponde: son tipos del JDK y de Spring,
 * no del dominio de {@code resource}, asi que mapearlos no crea ninguna dependencia entre
 * modulos. Los tres tienen consumidores reales en M05: ventana invertida, falta de contexto y
 * version desactualizada.
 *
 * <h2>El 409 de concurrencia sale con {@code type: concurrent-modification}</h2>
 *
 * <p>{@code DisponibilidadService} —igual que {@code EspacioService} y {@code CatalogoService}—
 * lanza el {@code OptimisticLockingFailureException} <b>plano</b>, y {@code GlobalExceptionHandler}
 * lo traduce a {@code concurrent-modification}, el mismo {@code type} que la subclase de JPA. Hasta
 * DP-21 salia el {@code conflict} generico y el mismo hecho tenia dos {@code type} segun el modulo;
 * la unificacion se hizo en el handler global y no aca, porque un {@code @RestControllerAdvice} sin
 * selectores aplica a TODOS los controllers.
 *
 * <p>La regla que esto NO relaja: <b>falta de contexto es 403 y nunca 401</b> —el interceptor del
 * frontend borra el token ante cualquier 401 y deja al usuario en un bucle de login—, y
 * <b>cross-tenant es 404 y nunca 403</b> —un 403 confirmaria que el id existe—. Las dos ya vienen
 * decididas del servicio; lo que este archivo tiene prohibido es deshacerlas al traducir.
 *
 * <h2>Orden</h2>
 *
 * <p>{@code HIGHEST_PRECEDENCE} es obligatorio, no cosmetico: {@code GlobalExceptionHandler}
 * tiene un manejador de {@code Exception} como red de contencion y Spring resuelve por advice
 * antes que por especificidad. Sin este orden, el advice global se tragaria estas excepciones y
 * las devolveria como 500.
 *
 * <h2>Que se publica y que no</h2>
 *
 * <p>Ninguna respuesta lleva nombres de clase, paquetes, SQL ni stack traces. Los conflictos SI
 * llevan los datos accionables: el id del bloque en conflicto y su horario —son filas del propio
 * tenant que el actor ya ve en el listado—, y {@code maximoDias} en la ventana demasiado amplia,
 * que es lo unico que le permite al cliente recortar solo en vez de mostrarle el error al
 * usuario.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DisponibilidadProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(DisponibilidadProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI BLOQUE_SOLAPADO = ProblemType.BLOQUE_SOLAPADO.uri();
	private static final URI BLOQUE_INACTIVO = ProblemType.BLOQUE_INACTIVO.uri();
	private static final URI BLOQUE_ALREADY_INACTIVE = ProblemType.BLOQUE_ALREADY_INACTIVE.uri();
	private static final URI PROFESIONAL_NO_VINCULADO = ProblemType.PROFESIONAL_NO_VINCULADO.uri();
	private static final URI VENTANA_DEMASIADO_AMPLIA =
			ProblemType.VENTANA_DEMASIADO_AMPLIA.uri();
	private static final URI EXCEPCION_ALREADY_INACTIVE =
			ProblemType.EXCEPCION_ALREADY_INACTIVE.uri();

	// =================================================================================
	// 404 — inexistente, de otro tenant, de otra sede o de otro profesional
	// =================================================================================

	/**
	 * Bloque inexistente, de otra sede, de otro tenant o <b>de otro profesional</b>.
	 *
	 * <p>Los cuatro casos responden lo mismo, a proposito. Distinguirlos permitiria recorrer ids
	 * consecutivos y reconstruir el horario de cada profesional del SaaS.
	 *
	 * <p>El cuarto caso merece una nota: el servicio comprueba ademas que el bloque sea del
	 * profesional de la ruta. Sin eso, un administrador legitimo editaria el bloque de otro
	 * profesional cambiando un id en la URL y la auditoria quedaria contra la membership
	 * equivocada.
	 *
	 * <p><b>Un bloque INACTIVO del propio profesional no es inaccesible:</b> sale por 409, no por
	 * aca. La distincion vive del lado del servicio y hoy no es observable en ninguna lectura de
	 * la API —ninguna expone bloques inactivos—; se vuelve observable el dia que haya filtro de
	 * estado.
	 */
	@ExceptionHandler(BloqueNotAccessibleException.class)
	public ProblemDetail handleBloqueNotAccessible(BloqueNotAccessibleException exception) {
		log.debug("Bloque de disponibilidad no accesible: bloqueId={}", exception.getBloqueId());
		return noEncontrado();
	}

	/**
	 * Profesional inexistente en el tenant, o de otro tenant.
	 *
	 * <p>404 y no 409, que es la diferencia con {@link ProfesionalNoVinculadoException}: "esa
	 * membership no existe para vos" y "esa membership existe pero no atiende en esta sede" son
	 * dos hechos distintos. Responder 409 al primero confirmaria que el id existe en algun lado.
	 */
	@ExceptionHandler(ProfesionalNotAccessibleException.class)
	public ProblemDetail handleProfesionalNotAccessible(ProfesionalNotAccessibleException e) {
		log.debug("Profesional no accesible desde disponibilidad: membershipId={}",
				e.getMembershipId());
		return noEncontrado();
	}

	/** Excepcion de disponibilidad inexistente, de otra sede o de otro tenant. */
	@ExceptionHandler(ExcepcionNotAccessibleException.class)
	public ProblemDetail handleExcepcionNotAccessible(ExcepcionNotAccessibleException exception) {
		log.debug("Excepcion de disponibilidad no accesible: excepcionId={}",
				exception.getExcepcionId());
		return noEncontrado();
	}

	// =================================================================================
	// 409 — el actor tiene el permiso; lo que no admite la operacion es el estado
	// =================================================================================

	/**
	 * El bloque pedido se pisa con otro bloque activo del mismo profesional en esa sede.
	 *
	 * <p>409 y no 400: el bloque no es invalido, esta en conflicto con otra fila del propio
	 * tenant. Se publican el id y el horario del bloque en conflicto porque sin ellos el mensaje
	 * es inaccionable —el usuario no sabe cual de sus bloques tiene que mirar— y son datos que ya
	 * ve en el listado.
	 *
	 * <p><b>Solapar no es ser contiguo ni ser identico.</b> La hora de fin es EXCLUSIVA, asi que
	 * 09:00-12:00 y 12:00-15:00 conviven; y un pedido que reproduce exactamente un bloque activo
	 * no llega aca: es idempotente y devuelve 200. Esta excepcion es el caso del medio.
	 *
	 * <p><b>Las excepciones de disponibilidad NO producen este conflicto</b>, y es deliberado:
	 * dos cierres que se pisan son los dos legitimos.
	 */
	@ExceptionHandler(BloqueSolapadoException.class)
	public ProblemDetail handleBloqueSolapado(BloqueSolapadoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"El horario pedido se pisa con otro bloque activo del profesional en esta sede.");
		problem.setTitle("Bloque de disponibilidad solapado");
		problem.setType(BLOQUE_SOLAPADO);
		problem.setProperty("bloqueEnConflictoId", exception.getBloqueEnConflictoId());
		problem.setProperty("diaSemana", exception.getDiaSemana());
		problem.setProperty("horaDesde", horaDeContrato(exception.getHoraDesde()));
		problem.setProperty("horaHasta", horaDeContrato(exception.getHoraHasta()));
		return problem;
	}

	/**
	 * Mutacion sobre un bloque dado de baja.
	 *
	 * <p>Dos {@code type} distintos para dos mensajes distintos: "no se puede editar un bloque
	 * dado de baja" y "ese bloque ya estaba dado de baja" se le cuentan distinto al usuario, y un
	 * solo codigo obligaria al frontend a leer la prosa del {@code detail}, que cambia sin previo
	 * aviso y esta en castellano. Es el mismo criterio que {@code EspacioProblemHandler} usa para
	 * los espacios.
	 *
	 * <p>409 y no 404 porque el servicio SI encontro la fila: lo que rechaza es el ESTADO.
	 */
	@ExceptionHandler(BloqueInactivoException.class)
	public ProblemDetail handleBloqueInactivo(BloqueInactivoException exception) {
		boolean segundaBaja = exception.getOperacion() == BloqueInactivoException.Operacion.BAJA;

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				segundaBaja
						? "El bloque de disponibilidad ya estaba dado de baja."
						: "El bloque de disponibilidad esta dado de baja: no admite ediciones.");
		problem.setTitle(segundaBaja ? "Bloque ya dado de baja" : "Bloque dado de baja");
		problem.setType(segundaBaja ? BLOQUE_ALREADY_INACTIVE : BLOQUE_INACTIVO);
		return problem;
	}

	/**
	 * La membership existe en el tenant pero no habilita a atender en ESA sede (RN-M05-001).
	 *
	 * <p>409 y no 403: no es una cuestion de permisos del actor —el administrador puede hacer
	 * esto—, es que el profesional no atiende ahi. Y no es 404 porque la membership existe y el
	 * actor ya la puede ver: negarla seria contradecir la lectura que se la devolvio.
	 *
	 * <p>Solo lo exigen las operaciones que crean disponibilidad NUEVA. Leer y dar de baja no lo
	 * piden: RN-M05-003 dice que desvincular no borra bloques ni autoria, y exigir vinculo vigente
	 * para leer dejaria al administrador sin poder revisar el horario de quien se fue.
	 */
	@ExceptionHandler(ProfesionalNoVinculadoException.class)
	public ProblemDetail handleProfesionalNoVinculado(ProfesionalNoVinculadoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"El profesional no tiene un vinculo vigente que lo habilite a atender en esta "
						+ "sede.");
		problem.setTitle("Profesional no vinculado a la sede");
		problem.setType(PROFESIONAL_NO_VINCULADO);
		problem.setProperty("membershipId", exception.getMembershipId());
		problem.setProperty("consultorioId", exception.getConsultorioId());
		return problem;
	}

	/**
	 * Baja de una excepcion que ya estaba dada de baja.
	 *
	 * <p>409 por el mismo motivo que el bloque: el servicio la encontro y lo que rechaza es el
	 * estado. "Ya estaba dada de baja" es informacion distinta de "no existe", y es la unica que
	 * le sirve al administrador que apreto el boton dos veces.
	 */
	@ExceptionHandler(ExcepcionInactivaException.class)
	public ProblemDetail handleExcepcionInactiva(ExcepcionInactivaException exception) {
		log.debug("Baja repetida de excepcion: excepcionId={}", exception.getExcepcionId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La excepcion de disponibilidad ya estaba dada de baja.");
		problem.setTitle("Excepcion ya dada de baja");
		problem.setType(EXCEPCION_ALREADY_INACTIVE);
		return problem;
	}

	// =================================================================================
	// 400 — el pedido es coherente pero el servicio no se compromete a responderlo
	// =================================================================================

	/**
	 * Ventana de consulta mas amplia que el tope.
	 *
	 * <p>400 y no 413: lo que esta mal es el parametro, no el tamano del cuerpo. Comparte el
	 * codigo con la ventana INVERTIDA —que sale por {@code IllegalArgumentException} y ya esta
	 * mapeada en el advice global—, y la diferencia no es cosmetica: son dos {@code type}
	 * distintos porque son dos cosas distintas que el cliente puede hacer. Ante una ventana
	 * invertida hay que corregir el pedido; ante una demasiado amplia alcanza con recortarla, y
	 * para eso viaja {@code maximoDias}.
	 *
	 * <p>El tope existe porque la disponibilidad efectiva NO esta materializada y se resuelve un
	 * dia por iteracion: sin el, un {@code desde=1970} es un scan y un problema de disponibilidad
	 * del servicio, no una consulta.
	 */
	@ExceptionHandler(VentanaDemasiadoAmpliaException.class)
	public ProblemDetail handleVentanaDemasiadoAmplia(VentanaDemasiadoAmpliaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"La ventana consultada supera el maximo de " + exception.getMaximoDias()
						+ " dias. Pedila por partes.");
		problem.setTitle("Ventana demasiado amplia");
		problem.setType(VENTANA_DEMASIADO_AMPLIA);
		problem.setProperty("maximoDias", exception.getMaximoDias());
		return problem;
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/**
	 * Un 404 uniforme, sin nada que permita distinguir "no existe" de "no es tuyo".
	 *
	 * <p>El {@code detail} es deliberadamente generico: cualquier variacion —"el bloque no
	 * existe" contra "el profesional no existe"— seria un oraculo, porque bastaria comparar los
	 * dos textos para saber que ids son reales.
	 */
	private static ProblemDetail noEncontrado() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND,
				"El recurso solicitado no existe o no esta disponible.");
		problem.setTitle("Recurso no encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}

	/**
	 * La hora tal como la publica el contrato: la medianoche es {@code "24:00"}.
	 *
	 * <p>El cuerpo del error se serializa como un mapa de propiedades sueltas y no pasa por los
	 * DTO, asi que sin esto un conflicto contra un bloque que llega a medianoche mostraria
	 * {@code 23:59:59.999999999} justo en el mensaje que el usuario SI lee.
	 */
	private static String horaDeContrato(java.time.LocalTime hora) {
		return com.akine.resource.api.dto.HoraDelDia.aTexto(hora);
	}
}
