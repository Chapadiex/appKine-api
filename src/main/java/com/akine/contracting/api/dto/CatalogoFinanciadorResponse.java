package com.akine.contracting.api.dto;

import com.akine.contracting.application.FinanciadorCatalogoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Financiador del catalogo global de la plataforma, con sus planes (DP-43)")
public record CatalogoFinanciadorResponse(

		@Schema(description = "Identificador en el catalogo. Es el que se adopta", example = "7")
		long id,

		@Schema(description = "Clave estable. Es el codigo que recibe la copia del centro",
				example = "OSDE")
		String codigo,

		@Schema(description = "Nombre visible", example = "OSDE")
		String nombre,

		@Schema(description = "Clasificacion. Clasifica, no habilita", example = "PREPAGA",
				allowableValues = {"OBRA_SOCIAL", "PREPAGA", "ART", "MUTUAL", "ORGANISMO_PUBLICO",
						"OTRO"})
		String tipo,

		@Schema(description = "CUIT normalizado a 11 digitos, o null", example = "30712345678")
		String cuit,

		@Schema(description = "Contacto administrativo general del financiador")
		String emailContacto,

		@Schema(description = "Telefono administrativo general del financiador")
		String telefonoContacto,

		@Schema(description = "Notas administrativas. Nunca contenido clinico")
		String observaciones,

		@Schema(description = "Ciclo de vida. Un centro solo ve los ACTIVOS", example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version,

		@Schema(description = "Ultima edicion de la entrada: de la cabecera o de cualquiera de sus "
				+ "planes, incluidas altas y bajas de plan")
		Instant actualizadoEn,

		@Schema(description = "Planes. En la consola de plataforma, todos; para un centro, solo los "
				+ "ACTIVOS, que son los que se copian al adoptar")
		List<PlanCatalogoResponse> planes,

		@Schema(description = "Solo en la lectura de un centro: su copia VIGENTE de esta entrada, si "
				+ "ya la adopto. Null si no la adopto, y siempre null en la consola de plataforma",
				example = "31")
		Long financiadorAdoptadoId,

		@Schema(description = "Solo en la lectura de un centro: true si la entrada cambio despues de "
				+ "que el centro adopto su copia. INFORMATIVO: la copia no se actualiza sola ni hay "
				+ "operacion que la re-sincronice")
		boolean actualizacionDisponible) {

	public static CatalogoFinanciadorResponse de(FinanciadorCatalogoView view) {
		return new CatalogoFinanciadorResponse(
				view.id(),
				view.codigo(),
				view.nombre(),
				view.tipo(),
				view.cuit(),
				view.emailContacto(),
				view.telefonoContacto(),
				view.observaciones(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version(),
				view.actualizadoEn(),
				view.planes().stream().map(PlanCatalogoResponse::de).toList(),
				view.financiadorAdoptadoId(),
				view.actualizacionDisponible());
	}
}
