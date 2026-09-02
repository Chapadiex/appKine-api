package com.akine.contracting.api;

import com.akine.contracting.domain.exception.ArancelNotAccessibleException;
import com.akine.contracting.domain.exception.ArancelSolapadoException;
import com.akine.contracting.domain.exception.ArancelYaInactivoException;
import com.akine.contracting.domain.exception.ConvenioCodigoTakenException;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import com.akine.contracting.domain.exception.ConvenioSolapadoException;
import com.akine.contracting.domain.exception.ConvenioYaInactivoException;
import com.akine.contracting.domain.exception.FinanciadorCodigoTakenException;
import com.akine.contracting.domain.exception.FinanciadorCuitTakenException;
import com.akine.contracting.domain.exception.FinanciadorInactivoException;
import com.akine.contracting.domain.exception.FinanciadorNombreTakenException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.FinanciadorYaInactivoException;
import com.akine.contracting.domain.exception.PlanCodigoTakenException;
import com.akine.contracting.domain.exception.PlanNombreTakenException;
import com.akine.contracting.domain.exception.PlanNotAccessibleException;
import com.akine.contracting.domain.exception.PlanYaInactivoException;
import com.akine.contracting.domain.exception.PracticaNoAccesibleException;
import com.akine.contracting.domain.exception.SedeNoAccesibleException;
import com.akine.platform.spi.problem.ProblemType;
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
 * Traduce las excepciones de dominio de {@code contracting} a Problem Details RFC 7807 (ADR-0005).
 *
 * <p><b>{@code HIGHEST_PRECEDENCE} con el mismo criterio que los otros handlers de modulo:</b> sin
 * el, {@code GlobalExceptionHandler} atrapa estas excepciones como {@code RuntimeException} y
 * devuelve un 500 generico. El orden no es una preferencia de estilo, es lo que hace que estos
 * {@code type} lleguen al cliente.
 *
 * <p><b>Lo que este handler NO mapea, y a proposito:</b> el
 * {@code OptimisticLockingFailureException} de la version desactualizada. Lo mapea el handler
 * global a {@code conflict}, y esta etapa no lo intercepta para emitir
 * {@code concurrent-modification} — que es lo que los contratos de 02.02 y 02.05 prometen y su
 * codigo no cumple. Unificar los dos {@code type} es una decision de contrato transversal que
 * excede a M15; mientras no se tome, lo honesto es documentar el que realmente sale.
 *
 * <h2>Por que un no-accesible es 404 y no 403</h2>
 *
 * <p>Un financiador inexistente y uno de otra organizacion responden lo mismo. Distinguirlos
 * confirmaria que ese id existe, y bastaria recorrer numeros para averiguar con que obras sociales
 * trabaja cada centro del SaaS: informacion comercial de un cliente, filtrada por la forma del
 * error.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContractingProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(ContractingProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI FINANCIADOR_CODIGO_TAKEN = ProblemType.FINANCIADOR_CODIGO_TAKEN.uri();
	private static final URI FINANCIADOR_NOMBRE_TAKEN = ProblemType.FINANCIADOR_NOMBRE_TAKEN.uri();
	private static final URI FINANCIADOR_CUIT_TAKEN = ProblemType.FINANCIADOR_CUIT_TAKEN.uri();
	private static final URI FINANCIADOR_INACTIVO = ProblemType.FINANCIADOR_INACTIVO.uri();
	private static final URI FINANCIADOR_ALREADY_INACTIVE =
			ProblemType.FINANCIADOR_ALREADY_INACTIVE.uri();
	private static final URI PLAN_CODIGO_TAKEN = ProblemType.PLAN_COBERTURA_CODIGO_TAKEN.uri();
	private static final URI PLAN_NOMBRE_TAKEN = ProblemType.PLAN_COBERTURA_NOMBRE_TAKEN.uri();
	private static final URI PLAN_INACTIVO = ProblemType.PLAN_COBERTURA_INACTIVO.uri();
	private static final URI PLAN_ALREADY_INACTIVE =
			ProblemType.PLAN_COBERTURA_ALREADY_INACTIVE.uri();
	private static final URI CONVENIO_CODIGO_TAKEN = ProblemType.CONVENIO_CODIGO_TAKEN.uri();
	private static final URI CONVENIO_SOLAPADO = ProblemType.CONVENIO_SOLAPADO.uri();
	private static final URI CONVENIO_INACTIVO = ProblemType.CONVENIO_INACTIVO.uri();
	private static final URI CONVENIO_ALREADY_INACTIVE =
			ProblemType.CONVENIO_ALREADY_INACTIVE.uri();
	private static final URI ARANCEL_SOLAPADO = ProblemType.ARANCEL_SOLAPADO.uri();
	private static final URI ARANCEL_INACTIVO = ProblemType.ARANCEL_INACTIVO.uri();
	private static final URI ARANCEL_ALREADY_INACTIVE = ProblemType.ARANCEL_ALREADY_INACTIVE.uri();

	// =================================================================================
	// No accesibles — 404
	// =================================================================================

	@ExceptionHandler(FinanciadorNotAccessibleException.class)
	public ProblemDetail handleFinanciadorNoAccesible(FinanciadorNotAccessibleException exception) {
		log.debug("Financiador no accesible: financiadorId={}", exception.getFinanciadorId());
		return noEncontrado("El financiador no existe.");
	}

	/**
	 * El plan no existe, es de otra organizacion, o es de otro financiador que el de la ruta.
	 *
	 * <p>Los tres responden 404 y con el mismo texto. El tercero puede sorprender —el plan existe y
	 * es del mismo tenant— y es deliberado: la ruta declara a que financiador pertenece, y
	 * resolverlo bajo un financiador que no es el suyo seria una respuesta que miente.
	 */
	@ExceptionHandler(PlanNotAccessibleException.class)
	public ProblemDetail handlePlanNoAccesible(PlanNotAccessibleException exception) {
		log.debug("Plan de cobertura no accesible: planId={}", exception.getPlanId());
		return noEncontrado("El plan de cobertura no existe.");
	}

	// =================================================================================
	// Invariantes del financiador — 409
	// =================================================================================

	@ExceptionHandler(FinanciadorCodigoTakenException.class)
	public ProblemDetail handleCodigoTaken(FinanciadorCodigoTakenException exception) {
		log.debug("Codigo de financiador en uso: codigo={}", exception.getCodigo());
		return conflicto(
				"Ya existe un financiador vigente con ese codigo. El codigo de uno dado de baja "
						+ "si se puede reusar.",
				"Codigo de financiador en uso",
				FINANCIADOR_CODIGO_TAKEN);
	}

	@ExceptionHandler(FinanciadorNombreTakenException.class)
	public ProblemDetail handleNombreTaken(FinanciadorNombreTakenException exception) {
		log.debug("Nombre de financiador en uso");
		return conflicto(
				"Ya existe un financiador vigente con ese nombre. La comparacion no distingue "
						+ "mayusculas ni acentos.",
				"Nombre de financiador en uso",
				FINANCIADOR_NOMBRE_TAKEN);
	}

	@ExceptionHandler(FinanciadorCuitTakenException.class)
	public ProblemDetail handleCuitTaken(FinanciadorCuitTakenException exception) {
		// El CUIT NO se loguea: es identidad fiscal de un tercero y el log estructurado no tiene
		// el control de acceso que si tiene la tabla de auditoria.
		log.debug("CUIT de financiador en uso");
		return conflicto(
				"Ya existe un financiador vigente con ese CUIT, cargado con otro codigo. Los "
						+ "guiones y los puntos no cuentan: se comparan los 11 digitos.",
				"CUIT de financiador en uso",
				FINANCIADOR_CUIT_TAKEN);
	}

	/**
	 * El financiador referenciado esta dado de baja.
	 *
	 * <p>Es el 409 que llega desde el alta de un PLAN, no desde el financiador. La baja de un
	 * financiador no cascadea: sus planes y las coberturas ya firmadas siguen resolviendo, y lo
	 * unico que se impide es crear planes nuevos bajo el.
	 */
	@ExceptionHandler(FinanciadorInactivoException.class)
	public ProblemDetail handleFinanciadorInactivo(FinanciadorInactivoException exception) {
		log.debug("Financiador dado de baja referenciado: financiadorId={}",
				exception.getFinanciadorId());
		return conflicto(
				"El financiador esta dado de baja y no admite planes nuevos. Los planes que ya "
						+ "tenia y las coberturas firmadas siguen resolviendo.",
				"Financiador dado de baja",
				FINANCIADOR_INACTIVO);
	}

	@ExceptionHandler(FinanciadorYaInactivoException.class)
	public ProblemDetail handleFinanciadorYaInactivo(FinanciadorYaInactivoException exception) {
		log.debug("Operacion sobre financiador ya inactivo: financiadorId={} operacion={}",
				exception.getFinanciadorId(), exception.getOperacion());

		return "dar de baja".equals(exception.getOperacion())
				? conflicto(
						"El financiador ya estaba dado de baja.",
						"Financiador ya dado de baja",
						FINANCIADOR_ALREADY_INACTIVE)
				: conflicto(
						"El financiador esta dado de baja: no admite ediciones. Sus datos "
								+ "historicos siguen siendo consultables.",
						"Financiador dado de baja",
						FINANCIADOR_INACTIVO);
	}

	// =================================================================================
	// Invariantes del plan — 409
	// =================================================================================

	@ExceptionHandler(PlanCodigoTakenException.class)
	public ProblemDetail handlePlanCodigoTaken(PlanCodigoTakenException exception) {
		log.debug("Codigo de plan en uso: codigo={}", exception.getCodigo());
		return conflicto(
				"Ya existe un plan vigente con ese codigo en este financiador. Otro financiador "
						+ "si puede tener un plan con el mismo codigo.",
				"Codigo de plan en uso",
				PLAN_CODIGO_TAKEN);
	}

	@ExceptionHandler(PlanNombreTakenException.class)
	public ProblemDetail handlePlanNombreTaken(PlanNombreTakenException exception) {
		log.debug("Nombre de plan en uso");
		return conflicto(
				"Ya existe un plan vigente con ese nombre en este financiador. La comparacion no "
						+ "distingue mayusculas ni acentos.",
				"Nombre de plan en uso",
				PLAN_NOMBRE_TAKEN);
	}

	@ExceptionHandler(PlanYaInactivoException.class)
	public ProblemDetail handlePlanYaInactivo(PlanYaInactivoException exception) {
		log.debug("Operacion sobre plan ya inactivo: planId={} operacion={}",
				exception.getPlanId(), exception.getOperacion());

		return "dar de baja".equals(exception.getOperacion())
				? conflicto(
						"El plan ya estaba dado de baja.",
						"Plan ya dado de baja",
						PLAN_ALREADY_INACTIVE)
				: conflicto(
						"El plan esta dado de baja: no admite ediciones. Las coberturas firmadas "
								+ "bajo el siguen resolviendo.",
						"Plan dado de baja",
						PLAN_INACTIVO);
	}

	// =================================================================================
	// No accesibles de M16 — 404
	// =================================================================================

	@ExceptionHandler(SedeNoAccesibleException.class)
	public ProblemDetail handleSedeNoAccesible(SedeNoAccesibleException exception) {
		log.debug("Sede no accesible: consultorioId={}", exception.getConsultorioId());
		return noEncontrado("La sede no existe.");
	}

	/**
	 * El convenio no existe, es de otra organizacion, o es de otra sede que la de la ruta.
	 *
	 * <p>Los tres responden 404 y con el mismo texto. El tercero puede sorprender —el convenio
	 * existe y es del mismo tenant— y es deliberado: la ruta declara a que sede pertenece
	 * (RN-M16-001) y resolverlo bajo otra seria una respuesta que miente.
	 */
	@ExceptionHandler(ConvenioNotAccessibleException.class)
	public ProblemDetail handleConvenioNoAccesible(ConvenioNotAccessibleException exception) {
		log.debug("Convenio no accesible: convenioId={}", exception.getConvenioId());
		return noEncontrado("El convenio no existe.");
	}

	@ExceptionHandler(ArancelNotAccessibleException.class)
	public ProblemDetail handleArancelNoAccesible(ArancelNotAccessibleException exception) {
		log.debug("Arancel no accesible: arancelId={}", exception.getArancelId());
		return noEncontrado("El arancel no existe.");
	}

	/**
	 * La practica del catalogo clinico no existe o no la ve este tenant.
	 *
	 * <p>404 y no 400: una practica de otra organizacion no es un dato mal formado, es un recurso
	 * que para quien pregunta no existe. Misma regla que cualquier otro cross-tenant.
	 */
	@ExceptionHandler(PracticaNoAccesibleException.class)
	public ProblemDetail handlePracticaNoAccesible(PracticaNoAccesibleException exception) {
		log.debug("Practica no accesible: practicaId={}", exception.getPracticaId());
		return noEncontrado("La practica no existe.");
	}

	// =================================================================================
	// Invariantes de M16 — 409
	// =================================================================================

	@ExceptionHandler(ConvenioCodigoTakenException.class)
	public ProblemDetail handleConvenioCodigoTaken(ConvenioCodigoTakenException exception) {
		log.debug("Codigo de convenio en uso: codigo={}", exception.getCodigo());
		return conflicto(
				"Ya existe un convenio vigente con ese codigo en esta sede. Otra sede si puede "
						+ "tener un convenio con el mismo codigo, y el de uno dado de baja se puede "
						+ "reusar.",
				"Codigo de convenio en uso",
				CONVENIO_CODIGO_TAKEN);
	}

	/**
	 * El 409 que define AKINE-03.05 (RN-M16-002).
	 *
	 * <p>Lleva el id y el periodo del convenio con el que choca, en {@code properties}: un 409 que
	 * solo dice "se solapa" obliga al administrador a buscar a mano cual de los suyos es, y la
	 * grilla de vigencias tiene esa fila a un click de distancia si sabe cual.
	 */
	@ExceptionHandler(ConvenioSolapadoException.class)
	public ProblemDetail handleConvenioSolapado(ConvenioSolapadoException exception) {
		log.debug("Convenio solapado con convenioId={}", exception.getConvenioExistenteId());

		ProblemDetail problem = conflicto(
				"Ya hay un convenio con ese financiador y ese plan en esta sede cuyo periodo se "
						+ "pisa con el que se pide (" + exception.getPeriodoExistente() + "). Cerra "
						+ "la vigencia del que esta antes de abrir el nuevo.",
				"Convenio solapado",
				CONVENIO_SOLAPADO);

		problem.setProperty("convenioExistenteId", exception.getConvenioExistenteId());
		problem.setProperty("periodoExistente", exception.getPeriodoExistente());
		return problem;
	}

	@ExceptionHandler(ArancelSolapadoException.class)
	public ProblemDetail handleArancelSolapado(ArancelSolapadoException exception) {
		log.debug("Arancel solapado con arancelId={}", exception.getArancelExistenteId());

		ProblemDetail problem = conflicto(
				"Ya hay un arancel de esa practica en este convenio cuyo periodo se pisa con el que "
						+ "se pide (" + exception.getPeriodoExistente() + "). Dos aranceles de la "
						+ "misma practica pueden convivir, pero no pisarse: cerra la vigencia del "
						+ "que esta.",
				"Arancel solapado",
				ARANCEL_SOLAPADO);

		problem.setProperty("arancelExistenteId", exception.getArancelExistenteId());
		problem.setProperty("periodoExistente", exception.getPeriodoExistente());
		return problem;
	}

	@ExceptionHandler(ConvenioYaInactivoException.class)
	public ProblemDetail handleConvenioYaInactivo(ConvenioYaInactivoException exception) {
		log.debug("Operacion sobre convenio ya inactivo: convenioId={} operacion={}",
				exception.getConvenioId(), exception.getOperacion());

		return "dar de baja".equals(exception.getOperacion())
				? conflicto(
						"El convenio ya estaba dado de baja.",
						"Convenio ya dado de baja",
						CONVENIO_ALREADY_INACTIVE)
				: conflicto(
						"El convenio esta dado de baja: no admite ediciones ni aranceles nuevos. Lo "
								+ "que ya se liquido bajo el sigue explicandose con su propio "
								+ "snapshot.",
						"Convenio dado de baja",
						CONVENIO_INACTIVO);
	}

	@ExceptionHandler(ArancelYaInactivoException.class)
	public ProblemDetail handleArancelYaInactivo(ArancelYaInactivoException exception) {
		log.debug("Operacion sobre arancel ya inactivo: arancelId={} operacion={}",
				exception.getArancelId(), exception.getOperacion());

		return "dar de baja".equals(exception.getOperacion())
				? conflicto(
						"El arancel ya estaba dado de baja.",
						"Arancel ya dado de baja",
						ARANCEL_ALREADY_INACTIVE)
				: conflicto(
						"El arancel esta dado de baja: no admite ediciones. Para volver a tarifar "
								+ "esa practica se carga uno nuevo.",
						"Arancel dado de baja",
						ARANCEL_INACTIVO);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setTitle("No encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}

	private static ProblemDetail conflicto(String detalle, String titulo, URI type) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalle);
		problem.setTitle(titulo);
		problem.setType(type);
		return problem;
	}
}
