package com.akine.billing.api.dto;

import com.akine.billing.application.EgresoCommand;
import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.TipoBeneficiario;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
		@NotNull CategoriaEgreso categoria,

		@Schema(
				description = "`COLABORADOR` exige `beneficiarioMembershipId` y **resuelve el nombre "
						+ "en el servidor**, congelandolo. `EXTERNO` exige `beneficiarioNombre`.",
				example = "COLABORADOR")
		@NotNull TipoBeneficiario tipoBeneficiario,

		@Schema(
				description = "El vinculo del colaborador, no su cuenta: la misma persona puede ser "
						+ "profesional en un centro y administrativa en otro. **Se valida solo al "
						+ "crear**: si el profesional se desvinculo el 30 de septiembre, el centro "
						+ "le sigue debiendo septiembre.",
				example = "412")
		Long beneficiarioMembershipId,

		@Schema(description = "Solo para `EXTERNO`. Con `COLABORADOR` se ignora.",
				example = "Estudio Contable Perez")
		@Size(max = 160) String beneficiarioNombre,

		@Schema(description = "CUIT o documento del beneficiario externo.", example = "30712345678")
		@Size(max = 32) String beneficiarioDocumento,

		@Schema(description = "Periodo liquidado. Los dos o ninguno.", example = "2026-09-01")
		LocalDate periodoDesde,

		@Schema(example = "2026-09-30")
		LocalDate periodoHasta,

		@Schema(example = "Honorarios de septiembre 2026")
		@NotBlank @Size(max = 280) String concepto,

		@Schema(description = "Decimal exacto, nunca float.", example = "185000.00")
		@NotNull @DecimalMin(value = "0.01") BigDecimal importeTotal,

		@Schema(example = "ARS")
		@NotBlank @Size(min = 3, max = 3) String moneda,

		@Schema(
				description = "Tipo de comprobante. **Opcional en borrador y obligatorio para "
						+ "confirmar**: un egreso confirmado sin respaldo documental es plata que "
						+ "salio sin papel.",
				example = "FACTURA_C")
		@Size(max = 24) String comprobanteTipo,

		@Schema(
				description = "El mismo comprobante del mismo beneficiario no se carga dos veces: "
						+ "409 `egreso-comprobante-duplicado`.",
				example = "0001-00000123")
		@Size(max = 40) String comprobanteNumero,

		@Schema(example = "2026-10-03")
		LocalDate comprobanteFecha,

		@Schema(
				description = "Clave del cliente para que un doble click no cargue dos veces la "
						+ "misma liquidacion. Reusarla con **otro** contenido devuelve 409.",
				example = "9d1a7b3c-55e2-4f10-bb02-7c9e1d4a6f88")
		@Size(max = 80) String idempotencyKey) {

	public EgresoCommand aDominio() {
		return new EgresoCommand(
				categoria, tipoBeneficiario, beneficiarioMembershipId,
				beneficiarioNombre, beneficiarioDocumento,
				periodoDesde, periodoHasta, concepto, importeTotal, moneda,
				comprobanteTipo, comprobanteNumero, comprobanteFecha, idempotencyKey);
	}
}
