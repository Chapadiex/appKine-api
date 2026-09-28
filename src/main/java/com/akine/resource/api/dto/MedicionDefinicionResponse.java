package com.akine.resource.api.dto;

import com.akine.resource.application.MedicionDefinicionView;
import com.akine.resource.spi.MedicionTipo;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una definicion de medida tal como la ve el cliente.
 *
 * <p>Se devuelve tambien cuando esta <b>INACTIVA</b>, con 200 y no con 404: RN-M06-001 y
 * RN-M06-002 exigen que un test discontinuado siga siendo legible, porque es lo que explica las
 * mediciones que ya lo usaron. Lo que una definicion inactiva rechaza son las operaciones nuevas,
 * y eso se responde con 409.
 *
 * <p>No hay campo {@code vigente}: a diferencia del resto del catalogo de M06, esta tabla no tiene
 * eje de vigencia. "Se puede elegir" y "esta activa" son lo mismo aca.
 */
@Schema(description = "Definicion de medida del examen fisico")
public record MedicionDefinicionResponse(

		@Schema(description = "Identificador de la definicion", example = "12")
		long id,

		@Schema(
				description = "GLOBAL si la sembro la plataforma, ORGANIZACION si es propia del "
						+ "centro. Lo global se ve y no se edita desde un tenant",
				example = "ORGANIZACION")
		String alcance,

		@Schema(
				description = "Organizacion duenia. null cuando la definicion es GLOBAL: no "
						+ "pertenece a ninguna",
				example = "7")
		Long organizationId,

		@Schema(description = "Clave estable de la medida", example = "ROM_RODILLA_FLEX")
		String codigo,

		@Schema(description = "Nombre visible", example = "ROM de rodilla en flexion")
		String name,

		@Schema(description = "Como se toma la medida")
		String descripcion,

		@Schema(description = "Que clase de valor admite", example = "NUMERICO")
		MedicionTipo tipo,

		@Schema(description = "Unidad. null en TEXTO y BOOLEANO", example = "grados")
		String unidad,

		@Schema(description = "Piso del rango admitido al registrar, INCLUSIVE", example = "0")
		BigDecimal minimo,

		@Schema(description = "Techo del rango admitido al registrar, INCLUSIVE", example = "160")
		BigDecimal maximo,

		@Schema(description = "Ciclo de vida administrativo", example = "ACTIVO")
		String estado,

		@Schema(description = "Cuando se dio de baja, en UTC. null si sigue activa")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. null si sigue activa")
		String deactivationReason,

		@Schema(
				description = "Version que hay que reenviar para editarla. Si quedo vieja, 409",
				example = "0")
		long version) {

	public static MedicionDefinicionResponse from(MedicionDefinicionView vista) {
		return new MedicionDefinicionResponse(
				vista.id(),
				vista.alcance(),
				vista.organizationId(),
				vista.codigo(),
				vista.name(),
				vista.descripcion(),
				vista.tipo(),
				vista.unidad(),
				vista.minimo(),
				vista.maximo(),
				vista.estado(),
				vista.deletedAt(),
				vista.deactivationReason(),
				vista.version());
	}
}
