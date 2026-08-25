package com.akine.resource.application;

import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.domain.CatalogoConcepto;
import com.akine.resource.domain.CatalogoTipo;
import com.akine.resource.domain.NomencladorItem;
import com.akine.resource.domain.Practica;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un concepto del catalogo, tal como sale del servicio.
 *
 * <p>Una sola vista para los cuatro conceptos, con los campos especificos en {@code null}
 * cuando no aplican. La alternativa —cuatro vistas casi identicas— obligaria al cliente a
 * aprender cuatro formas para leer lo mismo, y a la pantalla de administracion a tener cuatro
 * componentes que hacen lo mismo.
 *
 * <p><b>{@code alcance}, {@code estado} y {@code vigente} son los tres valores DERIVADOS que la
 * pantalla necesita y que no son columnas:</b>
 *
 * <ul>
 *   <li>{@code alcance} sale de si hay {@code organizationId}: dice si el concepto se puede
 *       editar desde este tenant o si es de plataforma y solo se puede pedir su cambio;</li>
 *   <li>{@code estado} es el ciclo de vida administrativo, ACTIVO o INACTIVO;</li>
 *   <li>{@code vigente} dice si se puede elegir AHORA, lo que ademas exige estar dentro de la
 *       ventana de vigencia. Una practica ACTIVA que entra en vigor el mes que viene tiene
 *       {@code estado = ACTIVO} y {@code vigente = false}, y la UI tiene que poder explicar por
 *       que no aparece en el selector.</li>
 * </ul>
 *
 * <p>Se publican calculados y no se dejan para el cliente porque calcularlos en TypeScript seria
 * repetir una regla de negocio que el backend ya decidio, y las dos copias divergirian.
 */
public record CatalogoConceptoView(

		long id,

		String tipo,

		String alcance,

		/** {@code null} cuando el concepto es global: no pertenece a ninguna organizacion. */
		Long organizationId,

		String codigo,

		String name,

		String descripcion,

		/** Solo en practicas. */
		Long especialidadId,

		/** Solo en vigencias de nomenclador. */
		Long nomencladorId,

		/** Solo en vigencias de nomenclador. */
		Long practicaId,

		/** Solo en vigencias de nomenclador. */
		BigDecimal valorReferencia,

		Instant validFrom,

		Instant validUntil,

		String estado,

		boolean vigente,

		Instant deletedAt,

		String deactivationReason,

		long version) {

	/** Vista de una especialidad o de un nomenclador, que no agregan campos propios. */
	public static CatalogoConceptoView de(
			CatalogoConcepto concepto, CatalogoTipo tipo, Instant at) {

		return construir(concepto, tipo, at, null, null, null, null);
	}

	public static CatalogoConceptoView dePractica(Practica practica, Instant at) {
		return construir(
				practica, CatalogoTipo.PRACTICA, at,
				practica.getEspecialidadId(), null, null, null);
	}

	public static CatalogoConceptoView deVigencia(NomencladorItem item, Instant at) {
		return construir(
				item, CatalogoTipo.NOMENCLADOR, at,
				null, item.getNomencladorId(), item.getPracticaId(), item.getValorReferencia());
	}

	private static CatalogoConceptoView construir(
			CatalogoConcepto concepto,
			CatalogoTipo tipo,
			Instant at,
			Long especialidadId,
			Long nomencladorId,
			Long practicaId,
			BigDecimal valorReferencia) {

		return new CatalogoConceptoView(
				concepto.getId(),
				tipo.name(),
				CatalogoAlcance.de(concepto.getOrganizationId()).name(),
				concepto.getOrganizationId(),
				concepto.getCodigo(),
				concepto.getName(),
				concepto.getDescripcion(),
				especialidadId,
				nomencladorId,
				practicaId,
				valorReferencia,
				concepto.getValidFrom(),
				concepto.getValidUntil(),
				concepto.isActive() ? "ACTIVO" : "INACTIVO",
				concepto.estaVigente(at),
				concepto.getDeletedAt(),
				concepto.getDeactivationReason(),
				concepto.getVersion());
	}
}
