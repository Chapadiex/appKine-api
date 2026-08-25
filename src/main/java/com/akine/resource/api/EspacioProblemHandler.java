package com.akine.resource.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.EspacioCapacityBelowOccupancyException;
import com.akine.resource.domain.exception.EspacioHasActiveReferencesException;
import com.akine.resource.domain.exception.EspacioInactiveException;
import com.akine.resource.domain.exception.EspacioNameTakenException;
import com.akine.resource.domain.exception.EspacioNotAccessibleException;
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
 * Traduccion de las excepciones de dominio de {@code resource} a Problem Details (RFC 7807).
 *
 * <h2>Por que no esta en {@code GlobalExceptionHandler}</h2>
 *
 * <p><b>No es duplicacion: es la unica ubicacion legal.</b> {@code GlobalExceptionHandler} vive
 * en {@code platform}, que es el modulo base. Mapear ahi estas excepciones exige importarlas, y
 * eso crea una dependencia {@code platform -> resource} que cierra un ciclo —{@code resource} ya
 * depende de {@code platform.spi} para la auditoria y el contexto de tenant—.
 * {@code ModuleArchitectureTest} lo rechaza por partida doble:
 * {@code modulos_solo_se_alcanzan_por_su_spi} y {@code sin_ciclos_entre_modulos}.
 *
 * <p>Lo que se comparte con {@code platform} es lo que importa: la <b>convencion</b>. Mismo
 * formato, mismos {@code type} del catalogo unico de {@link ProblemType}, misma regla de no
 * filtrar nada interno. Es el mismo advice propio que ya tienen {@code organization} e
 * {@code identity}.
 *
 * <h2>Orden</h2>
 *
 * <p>{@code HIGHEST_PRECEDENCE} es obligatorio, no cosmetico. {@code GlobalExceptionHandler}
 * tiene un manejador de {@code Exception} como red de contencion, y Spring resuelve por advice
 * antes que por especificidad: sin este orden, el advice global se tragaria estas excepciones y
 * las devolveria como 500.
 *
 * <p><b>Convive con {@code OrganizationProblemHandler}, que tiene el mismo orden.</b> No hay
 * conflicto: los dos declaran tipos de excepcion disjuntos, y Spring elige el advice que sabe
 * manejar la excepcion concreta. El empate de orden solo importaria si los dos manejaran el
 * mismo tipo.
 *
 * <h2>Que se publica y que no</h2>
 *
 * <p>Ninguna respuesta lleva nombres de clase, paquetes, SQL ni stack traces. Los conflictos de
 * capacidad SI llevan los dos numeros —lo pedido y lo comprometido—: es lo que el cliente
 * necesita para decirle al usuario a cuanto SI puede bajar, y son datos de su propio tenant.
 * Los nombres de los espacios <b>no</b> viajan en el cuerpo del error: el cliente ya los tiene y
 * repetirlos no agrega nada.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EspacioProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(EspacioProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI ESPACIO_NAME_TAKEN = ProblemType.ESPACIO_NAME_TAKEN.uri();
	private static final URI ESPACIO_INACTIVE = ProblemType.ESPACIO_INACTIVE.uri();
	private static final URI ESPACIO_ALREADY_INACTIVE = ProblemType.ESPACIO_ALREADY_INACTIVE.uri();
	private static final URI ESPACIO_CAPACITY_BELOW_OCCUPANCY =
			ProblemType.ESPACIO_CAPACITY_BELOW_OCCUPANCY.uri();
	private static final URI ESPACIO_HAS_ACTIVE_REFERENCES =
			ProblemType.ESPACIO_HAS_ACTIVE_REFERENCES.uri();
	private static final URI CONSULTORIO_INACTIVE = ProblemType.CONSULTORIO_INACTIVE.uri();

	/**
	 * Espacio inexistente, de otra sede o de otro tenant.
	 *
	 * <p><b>Los tres casos responden lo mismo, a proposito.</b> Distinguirlos permitiria recorrer
	 * ids consecutivos y averiguar cuantos boxes tiene cada centro del SaaS. Para quien no tiene
	 * acceso, el recurso no existe.
	 */
	@ExceptionHandler(EspacioNotAccessibleException.class)
	public ProblemDetail handleEspacioNotAccessible(EspacioNotAccessibleException exception) {
		log.debug("Espacio no accesible: espacioId={}", exception.getEspacioId());
		return noEncontrado();
	}

	/**
	 * Sede inexistente, de otro tenant, o fuera del contexto validado del request.
	 *
	 * <p>Tambien 404 y por el mismo motivo. El id pedido va al log, nunca a la respuesta.
	 */
	@ExceptionHandler(ConsultorioNotAccessibleException.class)
	public ProblemDetail handleConsultorioNotAccessible(ConsultorioNotAccessibleException e) {
		log.debug("Sede no accesible desde espacios: consultorioId={}", e.getConsultorioId());
		return noEncontrado();
	}

	/**
	 * Alta rechazada porque la sede esta dada de baja.
	 *
	 * <p>Reusa el {@code type} {@code consultorio-inactive} que 02.01 ya publico: es el mismo
	 * hecho contado desde otro modulo, y darle un codigo nuevo obligaria al frontend a manejar
	 * dos valores para el mismo mensaje.
	 */
	@ExceptionHandler(ConsultorioNotOperableException.class)
	public ProblemDetail handleConsultorioNotOperable(ConsultorioNotOperableException exception) {
		log.info("Alta de espacio rechazada por sede inactiva: consultorioId={}",
				exception.getConsultorioId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La sede esta dada de baja y no admite espacios nuevos.");
		problem.setTitle("Sede dada de baja");
		problem.setType(CONSULTORIO_INACTIVE);
		return problem;
	}

	/**
	 * Nombre repetido entre los espacios VIGENTES de la sede.
	 *
	 * <p>409 y no 400: el nombre no es invalido, esta ocupado. Y el conflicto es contra otra
	 * fila del propio tenant, asi que decirlo no revela nada que el actor no pueda ver ya en el
	 * listado.
	 *
	 * <p>Un espacio dado de baja NO produce este conflicto: su nombre queda libre. Es lo que
	 * hace posible reponer "Box 1" despues de una refaccion, y esta sostenido por el unique con
	 * centinela de la migracion V19.
	 */
	@ExceptionHandler(EspacioNameTakenException.class)
	public ProblemDetail handleEspacioNameTaken(EspacioNameTakenException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Ya existe un espacio vigente con ese nombre en la sede. "
						+ "El nombre de un espacio dado de baja si se puede reusar.");
		problem.setTitle("Nombre de espacio en uso");
		problem.setType(ESPACIO_NAME_TAKEN);
		return problem;
	}

	/**
	 * Mutacion sobre un espacio dado de baja.
	 *
	 * <p>Dos {@code type} distintos para dos mensajes distintos: "no se puede editar un espacio
	 * dado de baja" y "ese espacio ya estaba dado de baja" se le cuentan distinto al usuario, y
	 * darle al frontend un solo codigo lo obligaria a leer la prosa del {@code detail}, que
	 * cambia sin previo aviso y esta en castellano.
	 *
	 * <p>409 y no 404 porque el espacio se sigue leyendo con 200 (RN-M04-003): responder que no
	 * existe justo cuando se lo quiere editar contradiria la lectura que acaba de devolverlo.
	 */
	@ExceptionHandler(EspacioInactiveException.class)
	public ProblemDetail handleEspacioInactive(EspacioInactiveException exception) {
		boolean segundaBaja = exception.getOperacion() == EspacioInactiveException.Operacion.BAJA;

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				segundaBaja
						? "El espacio ya estaba dado de baja."
						: "El espacio esta dado de baja: no admite ediciones. Sus datos "
								+ "historicos siguen siendo consultables.");
		problem.setTitle(segundaBaja ? "Espacio ya dado de baja" : "Espacio dado de baja");
		problem.setType(segundaBaja ? ESPACIO_ALREADY_INACTIVE : ESPACIO_INACTIVE);
		return problem;
	}

	/**
	 * Reduccion de capacidad rechazada por la ocupacion ya comprometida.
	 *
	 * <p>Se publican los dos numeros deliberadamente: sin ellos el mensaje es inaccionable y el
	 * usuario no sabe a cuanto SI puede bajar. Son datos de su propio tenant y no revelan nada
	 * de la implementacion ni de ninguna persona — {@code occupancyType} es vocabulario del
	 * modulo que declaro la ocupacion, no un nombre ni un identificador.
	 *
	 * <p><b>Reservado en F2:</b> no lo emite nadie mientras no exista ningun modulo que reserve.
	 */
	@ExceptionHandler(EspacioCapacityBelowOccupancyException.class)
	public ProblemDetail handleCapacityBelowOccupancy(
			EspacioCapacityBelowOccupancyException exception) {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La capacidad pedida (" + exception.getRequestedCapacity()
						+ ") es menor que los lugares ya comprometidos ("
						+ exception.getCurrentOccupancy() + ").");
		problem.setTitle("Capacidad menor que la ocupacion comprometida");
		problem.setType(ESPACIO_CAPACITY_BELOW_OCCUPANCY);
		problem.setProperty("requestedCapacity", exception.getRequestedCapacity());
		problem.setProperty("currentOccupancy", exception.getCurrentOccupancy());
		problem.setProperty("occupancyType", exception.getOccupancyType());
		return problem;
	}

	/**
	 * Baja bloqueada por ocupacion vigente sobre el espacio.
	 *
	 * <p><b>Reservado en F2:</b> lo emitira el modulo de agenda cuando existan turnos futuros.
	 * Se publica desde ya para que la pantalla de confirmacion de baja se escriba una sola vez.
	 */
	@ExceptionHandler(EspacioHasActiveReferencesException.class)
	public ProblemDetail handleEspacioHasActiveReferences(
			EspacioHasActiveReferencesException exception) {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"El espacio tiene compromisos vigentes que impiden darlo de baja.");
		problem.setTitle("Espacio con referencias vigentes");
		problem.setType(ESPACIO_HAS_ACTIVE_REFERENCES);
		problem.setProperty("referenceType", exception.getReferenceType());
		problem.setProperty("referenceCount", exception.getCount());
		return problem;
	}

	/**
	 * Un 404 uniforme, sin nada que permita distinguir "no existe" de "no es tuyo".
	 *
	 * <p>El {@code detail} es deliberadamente generico. Cualquier variacion —"la sede no
	 * existe" contra "el espacio no existe"— seria un oraculo: bastaria comparar los dos textos
	 * para saber que ids son reales.
	 */
	private static ProblemDetail noEncontrado() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND,
				"El recurso solicitado no existe o no esta disponible.");
		problem.setTitle("Recurso no encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}
}
