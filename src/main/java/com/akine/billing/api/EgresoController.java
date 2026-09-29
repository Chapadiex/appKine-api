package com.akine.billing.api;

import com.akine.billing.api.dto.AnularEgresoRequest;
import com.akine.billing.api.dto.EgresoResponse;
import com.akine.billing.api.dto.PagoEgresoResponse;
import com.akine.billing.api.dto.RegistrarEgresoRequest;
import com.akine.billing.api.dto.RegistrarPagoEgresoRequest;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.PagoEgresoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Egresos y pagos a profesionales (M22).
 *
 * <p><b>Del lado del egreso tambien son tres cosas.</b> El egreso es lo que el centro <b>debe</b>;
 * el pago es el acto de saldarlo; el movimiento de caja es el hecho monetario. No es una sola: un
 * egreso confirmado y no pagado es una deuda viva, un pago parcial deja saldo, y un pago por
 * transferencia asienta un movimiento que no toca el cajon.
 *
 * <p>Todas las operaciones exigen <b>{@code caja:operate}</b> con la sede como alcance. La matriz
 * minima de §32 no tiene una fila "Registrar Egreso", y la mas cercana —*Operar Caja*— tiene
 * exactamente los titulares que M22 declara como actores: administrativo y administrador. Inventar
 * un permiso nuevo seria inventar una fila de la matriz.
 *
 * <p>{@code PROFESIONAL} no lo tiene, y aca importa mas que en la caja: sin esa exclusion, un
 * profesional podria cargarse a si mismo una liquidacion.
 */
@RestController
@RequestMapping(
		path = "/api/v1/consultorios/{consultorioId}/egresos",
		produces = {MediaType.APPLICATION_JSON_VALUE, "application/problem+json"})
@Tag(name = "Egresos", description = "Egresos, liquidaciones y pagos a profesionales (M22)")
public class EgresoController {

	private final EgresoService egresoService;
	private final PagoEgresoService pagoService;
	private final BillingApiActor apiActor;

	public EgresoController(
			EgresoService egresoService,
			PagoEgresoService pagoService,
			BillingApiActor apiActor) {

		this.egresoService = egresoService;
		this.pagoService = pagoService;
		this.apiActor = apiActor;
	}

	// =================================================================================
	// El compromiso
	// =================================================================================

	@PostMapping
	@Operation(
			operationId = "registrarEgreso",
			summary = "Registrar un egreso",
			description = """
					Carga un compromiso de pago. **Nace en `BORRADOR` y no afecta nada** \
					(RF-M22-001).

					**El borrador no es decoracion de CRUD.** Una liquidacion se arma mirando \
					papeles: se carga el importe, se busca la factura, se corrige el periodo. Sin \
					borrador, cada correccion seria una anulacion mas un alta nueva y el historico \
					se llenaria de anulaciones que no son errores sino tipeo.

					**El beneficiario puede ser un colaborador o alguien externo.** Con \
					`COLABORADOR` se pasa su *membership* —no su cuenta: la misma persona puede ser \
					profesional en un centro y administrativa en otro— y el servidor resuelve el \
					nombre y lo **congela**, para que una liquidacion de septiembre se pueda leer \
					en marzo sin depender de nada.

					**La vigencia del vinculo se valida solo aca.** Si el profesional se desvinculo \
					el 30 de septiembre, el centro le sigue debiendo septiembre: confirmar y pagar \
					un egreso viejo tiene que funcionar, porque lo contrario convertiria una \
					desvinculacion en una forma de no pagar.

					**Esta etapa no calcula liquidaciones.** El importe lo declara una persona.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Egreso registrado, en borrador"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `caja:operate` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`beneficiario-no-vinculado`, `egreso-comprobante-duplicado` o "
							+ "clave de idempotencia reusada con otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EgresoResponse> registrar(
			@PathVariable long consultorioId,
			@RequestBody @Valid RegistrarEgresoRequest request) {

		EgresoResponse cuerpo = EgresoResponse.de(
				egresoService.registrar(apiActor.current(), consultorioId, request.aDominio()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId
						+ "/egresos/" + cuerpo.id()))
				.body(cuerpo);
	}

	@PutMapping("/{egresoId}")
	@Operation(
			summary = "Corregir el borrador",
			description = """
					Reemplaza los datos editables **mientras el egreso siga en `BORRADOR`** \
					(RF-M22-001).

					**El beneficiario no se edita**: cambiarlo seria otro egreso, no una correccion \
					de este.

					Una vez confirmado, 409 `egreso-no-editable`. Un importe que cambiara debajo de \
					pagos ya asentados haria que el saldo dejara de reconciliar con el ledger de \
					caja **sin que nada fallara**, que es la peor forma de romperse.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Borrador corregido"),
			@ApiResponse(
					responseCode = "404",
					description = "El egreso o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`egreso-no-editable` o `egreso-comprobante-duplicado`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EgresoResponse> editar(
			@PathVariable long consultorioId,
			@PathVariable long egresoId,
			@RequestBody @Valid RegistrarEgresoRequest request) {

		return ResponseEntity.ok(EgresoResponse.de(
				egresoService.editar(
						apiActor.current(), consultorioId, egresoId, request.aDominio())));
	}

	@PostMapping("/{egresoId}/confirmacion")
	@Operation(
			operationId = "confirmarEgreso",
			summary = "Confirmar el egreso",
			description = """
					Punto de no retorno: el egreso deja de editarse y **recien ahora admite pagos** \
					(RF-M22-001).

					**Confirmar NO mueve la caja.** Lo que mueve la caja es el pago. Es como esta \
					etapa lee RN-M22-001 —"solo un egreso confirmado puede mover la caja"— porque \
					la letra estricta volveria contradictorias otras dos cosas del mismo documento: \
					un egreso debido y no pagado no podria existir, y el pago parcial seria \
					irrepresentable, porque no se puede sacar media vez la plata de un cajon.

					**Exige comprobante** (tipo y numero). Un egreso confirmado sin respaldo \
					documental es plata que salio sin papel. En borrador es opcional a proposito: \
					la liquidacion se arma antes de tener la factura en la mano.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Egreso confirmado"),
			@ApiResponse(
					responseCode = "400",
					description = "`egreso-sin-comprobante`: falta el respaldo documental",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El egreso o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`egreso-no-confirmable`: ya no era un borrador",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EgresoResponse> confirmar(
			@PathVariable long consultorioId,
			@PathVariable long egresoId) {

		return ResponseEntity.ok(EgresoResponse.de(
				egresoService.confirmar(apiActor.current(), consultorioId, egresoId)));
	}

	@PostMapping("/{egresoId}/anulacion")
	@Operation(
			operationId = "anularEgreso",
			summary = "Anular el egreso",
			description = """
					Anula el compromiso con motivo obligatorio (RF-M22-005).

					**No toca la caja**: un egreso que solo se debia nunca movio plata.

					**Se rechaza si quedan pagos vigentes** (409 `egreso-con-pagos`, con `yaPagado` \
					en el cuerpo). Anular el compromiso dejaria plata fuera del cajon sin nada que \
					la justifique. El orden correcto es anular primero los pagos —que es lo que la \
					devuelve— y despues el compromiso.

					**Anular no significa borrar** (RN-M22-002): la fila queda con su motivo, su \
					actor y su fecha.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Egreso anulado"),
			@ApiResponse(
					responseCode = "404",
					description = "El egreso o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`egreso-ya-anulado` o `egreso-con-pagos`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EgresoResponse> anular(
			@PathVariable long consultorioId,
			@PathVariable long egresoId,
			@RequestBody @Valid AnularEgresoRequest request) {

		return ResponseEntity.ok(EgresoResponse.de(
				egresoService.anular(
						apiActor.current(), consultorioId, egresoId, request.motivo())));
	}

	@GetMapping("/{egresoId}")
	@Operation(
			operationId = "verEgreso",
			summary = "Ver un egreso",
			description = "El detalle con **todos** sus pagos, incluidos los anulados (RF-M22-004). "
					+ "Ocultar un pago anulado haria que el egreso pareciera no haberse pagado "
					+ "nunca, y el historial de \"se pago el 10 y se anulo el 12\" es exactamente "
					+ "lo que una auditoria busca.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Egreso con sus pagos"),
			@ApiResponse(
					responseCode = "404",
					description = "El egreso o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<EgresoResponse> ver(
			@PathVariable long consultorioId,
			@PathVariable long egresoId) {

		return ResponseEntity.ok(EgresoResponse.de(
				egresoService.ver(apiActor.current(), consultorioId, egresoId)));
	}

	@GetMapping
	@Operation(
			operationId = "buscarEgreso",
			summary = "Consultar egresos",
			description = """
					Lista y filtra por fecha, categoria, beneficiario y estado (RF-M22-004).

					**Sin los pagos de cada fila**: traerlos seria el N+1 del primer listado que lo \
					use. El detalle los tiene.

					**Los periodos superpuestos no se impiden, se muestran.** Dos liquidaciones del \
					mismo profesional para el mismo mes son legitimas —una correccion, un segundo \
					concepto—, y un unique sobre el periodo convertiria un caso normal en un error. \
					Filtrar por beneficiario las pone una al lado de la otra, que es la unica \
					respuesta honesta.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Egresos"),
			@ApiResponse(
					responseCode = "404",
					description = "La sede no existe o es de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	@SuppressWarnings("checkstyle:ParameterNumber")
	public ResponseEntity<List<EgresoResponse>> buscar(
			@PathVariable long consultorioId,
			@RequestParam(required = false) String estado,
			@RequestParam(required = false) String categoria,
			@RequestParam(required = false) Long beneficiarioMembershipId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
			@RequestParam(defaultValue = "50") int limite,
			@RequestParam(defaultValue = "0") int desplazamiento) {

		return ResponseEntity.ok(
				egresoService.buscar(
								apiActor.current(), consultorioId, estado, categoria,
								beneficiarioMembershipId, desde, hasta, limite, desplazamiento)
						.stream()
						.map(EgresoResponse::de)
						.toList());
	}

	// =================================================================================
	// El pago — lo unico que mueve la caja
	// =================================================================================

	@PostMapping("/{egresoId}/pagos")
	@Operation(
			summary = "Registrar un pago",
			description = """
					Salda un egreso confirmado, total o parcialmente, y **asienta la salida en la \
					caja en la misma transaccion** (RF-M22-002, RN-M22-001).

					**Un solo medio por pago**: pagarle a un profesional mitad en efectivo y mitad \
					por transferencia son dos hechos distintos, con dos comprobantes y \
					probablemente dos dias. Son dos pagos.

					**Solo `EFECTIVO` afecta el arqueo y exige caja abierta.** Los demas medios \
					asientan su movimiento igual —para que la operatoria del dia este completa— y \
					no tocan el cajon: esa plata nunca estuvo ahi.

					**La caja nunca queda en negativo.** Un pago que no entra en el cajon se \
					rechaza con 409 `caja-saldo-insuficiente`; no deja la caja en rojo. Lo decide \
					una condicion del motor, asi que dos pagos concurrentes no pueden colarse los \
					dos: si dos administrativos pagan 50.000 cada uno contra un cajon de 80.000, el \
					segundo recibe el 409 con el saldo disponible en vez de dejar el arqueo de esa \
					noche registrando un faltante que nunca existio.

					**Manda `idempotencyKey`.** Sin ella, un doble click saca la plata dos veces.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Pago registrado y asentado en caja"),
			@ApiResponse(
					responseCode = "403",
					description = "Sin `caja:operate` en esa sede, o sin contexto de trabajo activo",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "404",
					description = "El egreso o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`egreso-no-pagable`, `egreso-saldo-insuficiente`, "
							+ "`caja-no-abierta`, `caja-saldo-insuficiente`, `caja-moneda-distinta` "
							+ "o clave de idempotencia reusada con otro pedido",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PagoEgresoResponse> pagar(
			@PathVariable long consultorioId,
			@PathVariable long egresoId,
			@RequestBody @Valid RegistrarPagoEgresoRequest request) {

		PagoEgresoResponse cuerpo = PagoEgresoResponse.de(
				pagoService.pagar(
						apiActor.current(), consultorioId, egresoId, request.aDominio()));

		return ResponseEntity
				.created(URI.create("/api/v1/consultorios/" + consultorioId
						+ "/egresos/" + egresoId + "/pagos/" + cuerpo.id()))
				.body(cuerpo);
	}

	@PostMapping("/{egresoId}/pagos/{pagoId}/anulacion")
	@Operation(
			summary = "Anular un pago",
			description = """
					Deshace un pago y **corrige la caja** (RF-M22-005, RF-M24-006).

					Tres efectos en la misma transaccion: el pago queda `ANULADO` —**no borrado**—, \
					la plata vuelve al cajon como una fila propia `REVERSION_DE_EGRESO` con motivo \
					y puntero al movimiento original, y el saldo vuelve al egreso.

					**La compensacion cae en la jornada abierta hoy, no en la del pago.** La \
					jornada original ya fue arqueada, y si el error afecto el conteo su diferencia \
					ya lo registro: reescribirla destruiria la unica evidencia de que hubo un \
					desvio. Y ademas, fisicamente, la plata entra al cajon hoy.

					**Un pago se anula una sola vez**, y una reversion no se revierte: para deshacer \
					una anulacion se registra un pago nuevo, que es honesto sobre lo que paso.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pago anulado y caja corregida"),
			@ApiResponse(
					responseCode = "404",
					description = "El pago, el egreso o la sede no existen, o son de otro tenant",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(
					responseCode = "409",
					description = "`pago-egreso-ya-anulado`, `movimiento-no-reversible`, "
							+ "`caja-no-abierta` o `caja-saldo-insuficiente`",
					content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
	public ResponseEntity<PagoEgresoResponse> anularPago(
			@PathVariable long consultorioId,
			@PathVariable long egresoId,
			@PathVariable long pagoId,
			@RequestBody @Valid AnularEgresoRequest request) {

		return ResponseEntity.ok(PagoEgresoResponse.de(
				pagoService.anularPago(
						apiActor.current(), consultorioId, egresoId, pagoId, request.motivo())));
	}
}
