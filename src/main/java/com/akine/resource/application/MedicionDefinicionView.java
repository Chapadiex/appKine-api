package com.akine.resource.application;

import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.domain.MedicionDefinicion;
import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionTipo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una definicion de medida, tal como sale del servicio.
 *
 * <p>{@code alcance} y {@code estado} son valores <b>derivados</b> que no son columnas y que la
 * pantalla necesita: el primero dice si la definicion se puede editar desde este tenant o si es
 * de plataforma y solo se puede pedir su cambio; el segundo es el ciclo de vida administrativo.
 * Se publican calculados porque calcularlos en TypeScript seria repetir una regla que el backend
 * ya decidio, y las dos copias divergirian.
 *
 * <p>No hay campo {@code vigente} —a diferencia de {@code CatalogoConceptoView}— porque esta tabla
 * no tiene el eje de vigencia: "se puede elegir" y "esta activa" son lo mismo aca.
 */
public record MedicionDefinicionView(

		long id,

		String alcance,

		/** {@code null} cuando la definicion es global: no pertenece a ninguna organizacion. */
		Long organizationId,

		String codigo,

		String name,

		String descripcion,

		MedicionTipo tipo,

		String unidad,

		BigDecimal minimo,

		BigDecimal maximo,

		String estado,

		Instant deletedAt,

		String deactivationReason,

		long version) {

	public static MedicionDefinicionView de(MedicionDefinicion definicion) {
		return new MedicionDefinicionView(
				definicion.getId(),
				CatalogoAlcance.de(definicion.getOrganizationId()).name(),
				definicion.getOrganizationId(),
				definicion.getCodigo(),
				definicion.getName(),
				definicion.getDescripcion(),
				definicion.getTipo(),
				definicion.getUnidad(),
				definicion.getMinimo(),
				definicion.getMaximo(),
				definicion.isActive() ? "ACTIVO" : "INACTIVO",
				definicion.getDeletedAt(),
				definicion.getDeactivationReason(),
				definicion.getVersion());
	}

	/**
	 * El snapshot que cruza hacia otros modulos.
	 *
	 * <p>Lleva {@code minimo} y {@code maximo} porque el consumidor los necesita para validar
	 * <b>al registrar</b>, y {@code version} porque es lo que copia en su fila para poder decir
	 * despues contra que redaccion del test se valido. No lleva ni {@code deletedAt} ni el motivo
	 * de la baja: el consumidor solo necesita saber si admite mediciones nuevas, y el detalle
	 * administrativo es asunto del duenio del catalogo.
	 */
	public static MedicionDefinicionSnapshot snapshotDe(MedicionDefinicion definicion) {
		return new MedicionDefinicionSnapshot(
				definicion.getId(),
				definicion.getOrganizationId(),
				definicion.getCodigo(),
				definicion.getName(),
				definicion.getTipo(),
				definicion.getUnidad(),
				definicion.getMinimo(),
				definicion.getMaximo(),
				definicion.isOperable(),
				definicion.getVersion());
	}
}
