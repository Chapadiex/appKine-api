package com.akine.activity.api.dto;

import com.akine.activity.application.DevengoDeClase;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** El efecto de una asistencia sobre la deuda de una oferta {@code POR_CLASE} (U-39, RF-M18-008). */
@Schema(
		name = "DevengoDeClase",
		description = "Que le hizo una asistencia a la deuda de la persona en una oferta `POR_CLASE`. "
				+ "La deuda nace cuando la persona estuvo (presente o presente con demora) y la "
				+ "ausencia no devenga. **Devengar no es cobrar**: se paga con `POST /cobros` "
				+ "(RF-M19-009), y el estado de la asistencia no depende del de la deuda "
				+ "(CA-M19-009-06).")
public record DevengoDeClaseResponse(

		@Schema(
				description = "`DEVENGADA`: nacio la deuda de la clase. `YA_DEVENGADA`: la asistencia "
						+ "ya tenia su deuda viva. `ANULADA`: una correccion a ausente anulo la deuda "
						+ "(no la borra). `CON_COBROS`: la correccion a ausente encontro la deuda con "
						+ "cobros; sigue viva y se regulariza reintegrando el cobro y anulandola. "
						+ "`SIN_DEUDA`: la correccion a ausente no encontro deuda viva. `SIN_PRECIO`: "
						+ "la oferta no tiene precio vigente el dia de la clase; la asistencia queda "
						+ "registrada igual. `SIN_CAMBIO`: la correccion no cambia si la persona "
						+ "estuvo. `NO_APLICA`: la oferta no cobra por clase o el resultado es ausente.",
				allowableValues = {"DEVENGADA", "YA_DEVENGADA", "ANULADA", "CON_COBROS", "SIN_DEUDA",
						"SIN_PRECIO", "SIN_CAMBIO", "NO_APLICA"},
				example = "DEVENGADA")
		String efecto,

		@Schema(description = "La deuda afectada. Ausente si no hay.", example = "9001")
		Long obligacionId,

		@Schema(description = "Importe congelado de la deuda: el precio vigente de la oferta el dia "
				+ "de la clase.", example = "6000.00")
		BigDecimal importe,

		@Schema(description = "Lo que falta pagar despues de este registro.", example = "6000.00")
		BigDecimal saldo,

		@Schema(description = "Moneda, ISO 4217.", example = "ARS")
		String moneda,

		@Schema(description = "Estado de la deuda despues de este registro.",
				allowableValues = {"PENDIENTE", "PARCIAL", "PAGADA", "ANULADA"}, example = "PENDIENTE")
		String estado) {

	public static DevengoDeClaseResponse de(DevengoDeClase devengo) {
		return devengo == null
				? null
				: new DevengoDeClaseResponse(devengo.efecto().name(), devengo.obligacionId(),
						devengo.importe(), devengo.saldo(), devengo.moneda(), devengo.estado());
	}
}
