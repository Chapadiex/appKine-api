package com.akine.assistant.api.dto;

import com.akine.assistant.application.RespuestaDelAsistente.AccionParaConfirmar;
import com.akine.assistant.spi.Tarjeta;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAsistencia;
import com.akine.assistant.spi.Tarjeta.TarjetaDeAtencion;
import com.akine.assistant.spi.Tarjeta.TarjetaDeLlegada;
import com.akine.assistant.spi.Tarjeta.TarjetaDePaciente;
import com.akine.assistant.spi.Tarjeta.TarjetaDePago;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Una accion que el asistente propone. Trae <b>un solo</b> bloque, el de su {@code tipo}: el cliente
 * lo muestra, deja corregirlo y lo devuelve tal cual quedo en la confirmacion.
 */
@Schema(name = "AccionPropuesta", description = "Una accion que el asistente propone y graba solo al confirmar")
public record AccionPropuestaResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Con lo que se confirma o se descarta")
		long id,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ASISTENCIA_CLASE", "PAGO",
				"ATENCION", "LLEGADA", "PACIENTE_ALTA", "PACIENTE_EDICION"}) String tipo,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Pago de Juan Pérez") String titulo,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hasta cuando se puede confirmar")
		Instant venceEn,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Lo que hay que saber; se puede confirmar igual")) List<String> avisos,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Por que no se puede confirmar. Con alguno, la confirmacion se rechaza"))
		List<String> bloqueos,
		TarjetaDeAsistencia asistencia,
		TarjetaDePago pago,
		TarjetaDeAtencion atencion,
		TarjetaDeLlegada llegada,
		TarjetaDePaciente paciente) {

	public static AccionPropuestaResponse de(AccionParaConfirmar a) {
		if (a == null) {
			return null;
		}
		Tarjeta t = a.tarjeta();
		return new AccionPropuestaResponse(a.id(), a.tipo().name(), a.titulo(), a.venceEn(), a.avisos(), a.bloqueos(),
				t instanceof TarjetaDeAsistencia x ? x : null,
				t instanceof TarjetaDePago x ? x : null,
				t instanceof TarjetaDeAtencion x ? x : null,
				t instanceof TarjetaDeLlegada x ? x : null,
				t instanceof TarjetaDePaciente x ? x : null);
	}
}
