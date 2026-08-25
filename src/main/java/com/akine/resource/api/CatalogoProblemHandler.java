package com.akine.resource.api;

import com.akine.platform.spi.problem.ProblemType;
import com.akine.resource.domain.exception.CatalogoCodeTakenException;
import com.akine.resource.domain.exception.CatalogoHasActiveReferencesException;
import com.akine.resource.domain.exception.CatalogoInactiveException;
import com.akine.resource.domain.exception.CatalogoNameTakenException;
import com.akine.resource.domain.exception.CatalogoNotAccessibleException;
import com.akine.resource.domain.exception.CatalogoScopeMismatchException;
import com.akine.resource.domain.exception.NomencladorVigenciaOverlapException;
import com.akine.resource.domain.exception.SolicitudDuplicadaException;
import com.akine.resource.domain.exception.SolicitudNotAccessibleException;
import com.akine.resource.domain.exception.SolicitudYaResueltaException;
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
 * Traduccion de las excepciones del catalogo clinico a Problem Details (RFC 7807).
 *
 * <h2>Por que es un advice propio y no una ampliacion de {@code EspacioProblemHandler}</h2>
 *
 * <p>Los dos viven en el mismo modulo, asi que la restriccion de ciclos no obliga a separarlos.
 * Lo que separa es el <b>vocabulario</b>: aquel habla de sedes y capacidad, este de duenios y
 * vigencias, y meterlos juntos produciria una clase de treinta manejadores sobre dos dominios
 * que no comparten ni un {@code type}. El orden empatado no genera conflicto porque los tipos de
 * excepcion son disjuntos: Spring elige el advice que sabe manejar la excepcion concreta.
 *
 * <h2>Orden</h2>
 *
 * <p>{@code HIGHEST_PRECEDENCE} es obligatorio, no cosmetico. {@code GlobalExceptionHandler}
 * tiene un manejador de {@code Exception} como red de contencion y Spring resuelve por advice
 * antes que por especificidad: sin este orden, el advice global se tragaria estas excepciones y
 * las devolveria como 500.
 *
 * <h2>Que se publica y que no</h2>
 *
 * <p>Ninguna respuesta lleva nombres de clase, paquetes, SQL ni stack traces. Los conflictos SI
 * llevan lo que el cliente necesita para actuar —el conteo de dependientes, la ventana en
 * conflicto—: son datos de su propio tenant, o del catalogo comun que ya puede leer.
 *
 * <p><b>Los nombres y codigos ajenos no viajan.</b> Un choque de codigo dice que el codigo esta
 * tomado y en que catalogo, nunca contra que fila: si el conflicto fue contra un concepto de
 * plataforma, el actor ya lo puede ver en su listado, y si fue contra uno propio, tambien. Lo que
 * no se hace es convertir el error en un buscador.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogoProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(CatalogoProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI CATALOGO_CODE_TAKEN = ProblemType.CATALOGO_CODE_TAKEN.uri();
	private static final URI CATALOGO_NAME_TAKEN = ProblemType.CATALOGO_NAME_TAKEN.uri();
	private static final URI CATALOGO_INACTIVE = ProblemType.CATALOGO_INACTIVE.uri();
	private static final URI CATALOGO_ALREADY_INACTIVE =
			ProblemType.CATALOGO_ALREADY_INACTIVE.uri();
	private static final URI CATALOGO_REFERENCE_INACTIVE =
			ProblemType.CATALOGO_REFERENCE_INACTIVE.uri();
	private static final URI CATALOGO_HAS_ACTIVE_REFERENCES =
			ProblemType.CATALOGO_HAS_ACTIVE_REFERENCES.uri();
	private static final URI CATALOGO_SCOPE_MISMATCH = ProblemType.CATALOGO_SCOPE_MISMATCH.uri();
	private static final URI NOMENCLADOR_VIGENCIA_OVERLAP =
			ProblemType.NOMENCLADOR_VIGENCIA_OVERLAP.uri();
	private static final URI SOLICITUD_DUPLICADA = ProblemType.CATALOGO_SOLICITUD_DUPLICADA.uri();
	private static final URI SOLICITUD_YA_RESUELTA =
			ProblemType.CATALOGO_SOLICITUD_YA_RESUELTA.uri();

	/**
	 * Concepto inexistente o de otro tenant.
	 *
	 * <p><b>Los dos casos responden lo mismo, a proposito</b> (ADR-0018). Distinguirlos
	 * permitiria recorrer ids consecutivos y averiguar que practicas propias tiene cada centro
	 * del SaaS, que es informacion comercial de sus clientes.
	 */
	@ExceptionHandler(CatalogoNotAccessibleException.class)
	public ProblemDetail handleNotAccessible(CatalogoNotAccessibleException exception) {
		log.debug("Concepto de catalogo no accesible: conceptoId={}", exception.getConceptoId());
		return noEncontrado();
	}

	/** Solicitud inexistente o de otro tenant. Mismo criterio. */
	@ExceptionHandler(SolicitudNotAccessibleException.class)
	public ProblemDetail handleSolicitudNotAccessible(SolicitudNotAccessibleException exception) {
		log.debug("Solicitud de catalogo no accesible: solicitudId={}",
				exception.getSolicitudId());
		return noEncontrado();
	}

	/**
	 * Codigo repetido entre los conceptos VIGENTES del mismo duenio.
	 *
	 * <p>409 y no 400: el codigo no es invalido, esta ocupado. Se publica en que catalogo choco
	 * porque son dos acciones distintas para el usuario —pedirle a la plataforma que lo revise,
	 * o editar el suyo—.
	 */
	@ExceptionHandler(CatalogoCodeTakenException.class)
	public ProblemDetail handleCodeTaken(CatalogoCodeTakenException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				exception.isGlobal()
						? "Ya existe un concepto vigente con ese codigo en el catalogo de "
								+ "plataforma."
						: "Ya existe un concepto vigente con ese codigo en el catalogo de la "
								+ "organizacion. El codigo de uno dado de baja si se puede reusar.");
		problem.setTitle("Codigo de catalogo en uso");
		problem.setType(CATALOGO_CODE_TAKEN);
		problem.setProperty("alcance", exception.isGlobal() ? "GLOBAL" : "ORGANIZACION");
		return problem;
	}

	/**
	 * Nombre repetido entre los conceptos vigentes del mismo duenio.
	 *
	 * <p>La comparacion es insensible a mayusculas y acentos y la hace la collation de la base,
	 * no una columna normalizada: por eso dos grafias del mismo nombre chocan sin que exista
	 * ninguna segunda definicion de "igual" que mantener sincronizada.
	 */
	@ExceptionHandler(CatalogoNameTakenException.class)
	public ProblemDetail handleNameTaken(CatalogoNameTakenException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Ya existe un concepto vigente con ese nombre. La comparacion no distingue "
						+ "mayusculas ni acentos.");
		problem.setTitle("Nombre de catalogo en uso");
		problem.setType(CATALOGO_NAME_TAKEN);
		problem.setProperty("alcance", exception.isGlobal() ? "GLOBAL" : "ORGANIZACION");
		return problem;
	}

	/**
	 * Mutacion sobre un concepto dado de baja, o referencia a uno dado de baja.
	 *
	 * <p>Tres {@code type} para tres mensajes distintos: "no se puede editar", "ya estaba dado
	 * de baja" y "eso que estas referenciando esta dado de baja" se le cuentan distinto al
	 * usuario, y darle al frontend un solo codigo lo obligaria a leer la prosa del
	 * {@code detail}, que cambia sin previo aviso y esta en castellano.
	 *
	 * <p>409 y no 404 porque el concepto se sigue leyendo con 200 (RN-M06-001): responder que no
	 * existe justo cuando se lo quiere editar contradiria la lectura que acaba de devolverlo.
	 */
	@ExceptionHandler(CatalogoInactiveException.class)
	public ProblemDetail handleInactive(CatalogoInactiveException exception) {
		return switch (exception.getOperacion()) {
			case BAJA -> problema(
					"El concepto ya estaba dado de baja.",
					"Concepto ya dado de baja",
					CATALOGO_ALREADY_INACTIVE);
			case EDICION -> problema(
					"El concepto esta dado de baja: no admite ediciones. Sus datos historicos "
							+ "siguen siendo consultables.",
					"Concepto dado de baja",
					CATALOGO_INACTIVE);
			case REFERENCIA -> problema(
					"El concepto referenciado esta dado de baja y no admite conceptos nuevos "
							+ "colgando de el.",
					"Referencia dada de baja",
					CATALOGO_REFERENCE_INACTIVE);
		};
	}

	/**
	 * Baja bloqueada porque el concepto todavia sostiene otros conceptos vigentes.
	 *
	 * <p>El conteo se publica deliberadamente: sin el el mensaje es inaccionable y el usuario no
	 * sabe cuantas practicas tiene que dar de baja primero. Son datos de su propio catalogo.
	 */
	@ExceptionHandler(CatalogoHasActiveReferencesException.class)
	public ProblemDetail handleHasActiveReferences(
			CatalogoHasActiveReferencesException exception) {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"El concepto tiene " + exception.getCount() + " conceptos vigentes colgando: "
						+ "hay que darlos de baja primero. Estar usado historicamente NO impide "
						+ "la baja; lo que la impide es dejar algo vigente huerfano.");
		problem.setTitle("Concepto con dependientes vigentes");
		problem.setType(CATALOGO_HAS_ACTIVE_REFERENCES);
		problem.setProperty("referenceType", exception.getReferenceType());
		problem.setProperty("referenceCount", exception.getCount());
		return problem;
	}

	/**
	 * Un concepto global intentando depender de uno contextual.
	 *
	 * <p>El id de la referencia SI viaja: es el que el cliente acaba de mandar, asi que
	 * devolverlo no revela nada nuevo, y sin el la pantalla no puede senalar el campo.
	 */
	@ExceptionHandler(CatalogoScopeMismatchException.class)
	public ProblemDetail handleScopeMismatch(CatalogoScopeMismatchException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Un concepto global no puede depender de uno propio de una organizacion: dar de "
						+ "baja el segundo dejaria huerfano al primero para todos los demas "
						+ "tenants.");
		problem.setTitle("Alcance incompatible");
		problem.setType(CATALOGO_SCOPE_MISMATCH);
		problem.setProperty("referenciaId", exception.getReferenciaId());
		return problem;
	}

	/**
	 * Dos vigencias del mismo codigo que se pisan.
	 *
	 * <p>La ventana en conflicto se publica para que la pantalla pueda decir contra que choco,
	 * en vez de obligar al usuario a recorrer la lista buscandolo. Es dato de su propio
	 * nomenclador.
	 */
	@ExceptionHandler(NomencladorVigenciaOverlapException.class)
	public ProblemDetail handleOverlap(NomencladorVigenciaOverlapException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"Ya hay una vigencia de ese codigo que se solapa con la ventana pedida. Para "
						+ "actualizar un codigo hay que CERRAR la vigencia anterior y abrir una "
						+ "nueva: el fin de vigencia es exclusivo, asi que cerrar y abrir en el "
						+ "mismo instante no se solapa.");
		problem.setTitle("Vigencias superpuestas");
		problem.setType(NOMENCLADOR_VIGENCIA_OVERLAP);
		problem.setProperty("codigo", exception.getCodigo());
		problem.setProperty("desdeExistente", exception.getDesdeExistente());
		problem.setProperty("hastaExistente", exception.getHastaExistente());
		return problem;
	}

	/** Segundo pedido identico mientras el primero sigue pendiente. */
	@ExceptionHandler(SolicitudDuplicadaException.class)
	public ProblemDetail handleSolicitudDuplicada(SolicitudDuplicadaException exception) {
		log.debug("Solicitud duplicada rechazada");
		return problema(
				"Ya hay una solicitud PENDIENTE por ese mismo concepto. Volver a pedir algo ya "
						+ "rechazado si esta permitido.",
				"Solicitud duplicada",
				SOLICITUD_DUPLICADA);
	}

	/** Resolucion sobre una solicitud ya aprobada o rechazada. */
	@ExceptionHandler(SolicitudYaResueltaException.class)
	public ProblemDetail handleSolicitudYaResuelta(SolicitudYaResueltaException exception) {
		log.info("Resolucion sobre solicitud ya resuelta: solicitudId={}",
				exception.getSolicitudId());
		return problema(
				"La solicitud ya fue aprobada o rechazada. La resolucion es terminal.",
				"Solicitud ya resuelta",
				SOLICITUD_YA_RESUELTA);
	}

	private static ProblemDetail problema(String detalle, String titulo, URI tipo) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalle);
		problem.setTitle(titulo);
		problem.setType(tipo);
		return problem;
	}

	/**
	 * Un 404 uniforme, sin nada que permita distinguir "no existe" de "no es tuyo".
	 *
	 * <p>El {@code detail} es deliberadamente generico: cualquier variacion seria un oraculo,
	 * porque bastaria comparar los dos textos para saber que ids son reales.
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
