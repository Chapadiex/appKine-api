package com.akine.resource.api.dto;

import com.akine.resource.application.CatalogoConceptoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un concepto del catalogo clinico: especialidad, practica, nomenclador o vigencia.
 *
 * <h2>Los tres campos derivados que la pantalla necesita</h2>
 *
 * <ul>
 *   <li><b>{@code alcance}</b> — GLOBAL u ORGANIZACION. Es lo que le dice a la UI si el boton de
 *       editar tiene sentido: un concepto global no lo puede tocar un tenant, y ofrecerle el
 *       boton para despues devolverle un 403 es la peor version posible de esa pantalla. Lo que
 *       si puede hacer es pedir su alta o su cambio
 *       ({@code POST /api/v1/catalogo-solicitudes}).</li>
 *   <li><b>{@code estado}</b> — ACTIVO o INACTIVO: el ciclo de vida administrativo.</li>
 *   <li><b>{@code vigente}</b> — si se puede ELEGIR ahora, lo que ademas exige estar dentro de
 *       la ventana. Una practica ACTIVA que entra en vigor el mes que viene tiene
 *       {@code estado = ACTIVO} y {@code vigente = false}, y la UI tiene que poder explicar por
 *       que no aparece en el selector.</li>
 * </ul>
 *
 * <p>Los tres se calculan en el backend y no en el cliente: hacerlo en TypeScript seria repetir
 * una regla de negocio ya decidida, y las dos copias divergirian.
 *
 * <h2>Campos que vienen en null segun el tipo</h2>
 *
 * <p>{@code especialidadId} solo en practicas; {@code nomencladorId}, {@code practicaId} y
 * {@code valorReferencia} solo en vigencias. Un schema unico con nulos es preferible a cuatro
 * schemas casi identicos: la pantalla de administracion es una sola.
 */
@Schema(description = "Concepto del catalogo clinico, global de plataforma o propio del tenant")
public record CatalogoConceptoResponse(

		@Schema(description = "Identificador del concepto", example = "1")
		long id,

		@Schema(description = "Que clase de concepto es",
				example = "PRACTICA",
				allowableValues = {"ESPECIALIDAD", "PRACTICA", "NOMENCLADOR"})
		String tipo,

		@Schema(description = "Duenio del concepto",
				example = "ORGANIZACION",
				allowableValues = {"GLOBAL", "ORGANIZACION"})
		String alcance,

		@Schema(description = "Organizacion propietaria. null cuando el concepto es GLOBAL",
				example = "1")
		Long organizationId,

		@Schema(description = "Clave estable. No cambia nunca", example = "KIN-001")
		String codigo,

		@Schema(description = "Nombre visible", example = "Sesion de kinesiologia motora")
		String name,

		@Schema(description = "Descripcion libre, o null")
		String descripcion,

		@Schema(description = "Especialidad a la que pertenece. Solo en practicas", example = "1")
		Long especialidadId,

		@Schema(description = "Nomenclador al que pertenece. Solo en vigencias", example = "1")
		Long nomencladorId,

		@Schema(description = "Practica que codifica. Solo en vigencias", example = "1")
		Long practicaId,

		@Schema(description = "Valor publicado por el nomenclador para esta vigencia. NO es el "
				+ "arancel cobrable. Solo en vigencias", example = "1250.0000")
		BigDecimal valorReferencia,

		@Schema(description = "Instante UTC desde el que se puede elegir",
				example = "2026-09-01T00:00:00Z")
		Instant validFrom,

		@Schema(description = "Instante UTC hasta el que se puede elegir, exclusivo. "
				+ "null = sin fin previsto")
		Instant validUntil,

		@Schema(description = "Ciclo de vida administrativo, derivado",
				example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Si se puede elegir AHORA. Exige estado ACTIVO Y estar dentro de "
				+ "la ventana de vigencia", example = "true")
		boolean vigente,

		@Schema(description = "Instante UTC de la baja logica, o null si esta activo")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja, o null")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version) {

	public static CatalogoConceptoResponse from(CatalogoConceptoView view) {
		return new CatalogoConceptoResponse(
				view.id(),
				view.tipo(),
				view.alcance(),
				view.organizationId(),
				view.codigo(),
				view.name(),
				view.descripcion(),
				view.especialidadId(),
				view.nomencladorId(),
				view.practicaId(),
				view.valorReferencia(),
				view.validFrom(),
				view.validUntil(),
				view.estado(),
				view.vigente(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version());
	}
}
