package com.akine.billing.api.dto;

import com.akine.billing.application.EgresoCommand;
import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.TipoBeneficiario;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta o correccion de un egreso (RF-M22-001).
 *
 * <p><b>El importe lo declara una persona.</b> Esta etapa no calcula liquidaciones —ni por sesion,
 * ni por hora, ni por porcentaje—: el plan dice "sin inventar regla remunerativa", y la base de
 * pago por clase o actividad es de la segunda entrega.
 */
@Schema(
		name = "RegistrarEgreso",
		description = "Lo que el centro DEBE a un beneficiario. Nace en **BORRADOR** y no afecta "
				+ "nada hasta confirmarse; lo que mueve la caja es el pago, no la confirmacion.")
public record RegistrarEgresoRequest(

		@Schema(description = "En que se fue la plata. Filtra y totaliza (RF-M22-004).",
				example = "HONORARIOS_PROFESIONALES")
		@NotNull(message = "La categoria es obligatoria") CategoriaEgreso categoria,

		@Schema(
				description = "`COLABORADOR` exige `beneficiarioMembershipId` y **resuelve el nombre "
						+ "en el servidor**, congelandolo. `EXTERNO` exige `beneficiarioNombre`.",
				example = "COLABORADOR")
		@NotNull(message = "El tipo de beneficiario es obligatorio") TipoBeneficiario tipoBeneficiario,

		@Schema(
				description = "El vinculo del colaborador, no su cuenta: la misma persona puede ser "
						+ "profesional en un centro y administrativa en otro. **Se valida solo al "
						+ "crear**: si el profesional se desvinculo el 30 de septiembre, el centro "
						+ "le sigue debiendo septiembre.",
				example = "412")
		Long beneficiarioMembershipId,

		@Schema(description = "Solo para `EXTERNO`. Con `COLABORADOR` se ignora.",
				example = "Estudio Contable Perez")
		@Size(max = 160, message = "El nombre del beneficiario no puede superar los 160 caracteres")
		String beneficiarioNombre,

		@Schema(description = "CUIT o documento del beneficiario externo.", example = "30712345678")
		@Size(max = 32, message = "El documento del beneficiario no puede superar los 32 caracteres")
		String beneficiarioDocumento,

		@Schema(description = "Periodo liquidado. Los dos o ninguno.", example = "2026-09-01")
		LocalDate periodoDesde,

		@Schema(example = "2026-09-30")
		LocalDate periodoHasta,

		@Schema(example = "Honorarios de septiembre 2026")
		@NotBlank(message = "El concepto es obligatorio")
		@Size(max = 280, message = "El concepto no puede superar los 280 caracteres") String concepto,

		@Schema(description = "Decimal exacto, nunca float.", example = "185000.00")
		@NotNull(message = "El importe total es obligatorio")
		@DecimalMin(value = "0.01", message = "El importe total tiene que ser mayor que cero") BigDecimal importeTotal,

		@Schema(example = "ARS")
		@NotBlank(message = "La moneda es obligatoria")
		@Size(min = 3, max = 3, message = "La moneda debe tener 3 letras") String moneda,

		@Schema(
				description = "Tipo de comprobante. **Opcional en borrador y obligatorio para "
						+ "confirmar**: un egreso confirmado sin respaldo documental es plata que "
						+ "salio sin papel.",
				example = "FACTURA_C")
		@Size(max = 24, message = "El tipo de comprobante no puede superar los 24 caracteres") String comprobanteTipo,

		@Schema(
				description = "El mismo comprobante del mismo beneficiario no se carga dos veces: "
						+ "409 `egreso-comprobante-duplicado`.",
				example = "0001-00000123")
		@Size(max = 40, message = "El numero de comprobante no puede superar los 40 caracteres") String comprobanteNumero,

		@Schema(example = "2026-10-03")
		LocalDate comprobanteFecha,

		@Schema(
				description = "Clave del cliente para que un doble click no cargue dos veces la "
						+ "misma liquidacion. Reusarla con **otro** contenido devuelve 409.",
				example = "9d1a7b3c-55e2-4f10-bb02-7c9e1d4a6f88")
		@Size(max = 80, message = "La clave de idempotencia no puede superar los 80 caracteres") String idempotencyKey,

		@Schema(
				description = "Oferta de esta sede a la que se imputa el costo (DP-39): es lo que la "
						+ "rentabilidad por servicio del reporte `SERVICIOS` resta de sus ingresos. "
						+ "**Opcional**: sin oferta el egreso va a la fila \"sin imputar\" (alquiler, "
						+ "impuestos). Una oferta de otra sede o de otro tenant es 404.",
				example = "18")
		@Positive(message = "La oferta no es valida") Long ofertaId) {

	public EgresoCommand aDominio() {
		return new EgresoCommand(
				categoria, tipoBeneficiario, beneficiarioMembershipId,
				beneficiarioNombre, beneficiarioDocumento,
				periodoDesde, periodoHasta, concepto, importeTotal, moneda,
				comprobanteTipo, comprobanteNumero, comprobanteFecha, idempotencyKey, ofertaId);
	}
}
