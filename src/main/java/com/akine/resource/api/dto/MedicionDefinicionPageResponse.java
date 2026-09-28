package com.akine.resource.api.dto;

import com.akine.resource.application.MedicionDefinicionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina de definiciones de medida.
 *
 * <p>Misma forma que el resto de los listados paginados de la API, por la misma razon: el cliente
 * aprende una sola estructura de paginado y no una por endpoint.
 *
 * <p><b>Nota honesta sobre el paginado</b>, identica a la de {@code CatalogoConceptoPageResponse}:
 * la fuente devuelve todas las definiciones que cumplen el filtro y el recorte se hace en memoria.
 * Es aceptable porque el filtro por duenio, estado y texto ya ocurrio en la base y el catalogo de
 * medidas de un centro es chico por construccion — decenas, no miles. El dia que deje de serlo, el
 * recorte baja al repositorio y <b>este contrato no cambia</b>.
 */
@Schema(description = "Pagina de definiciones de medida, segun los filtros pedidos")
public record MedicionDefinicionPageResponse(

		@Schema(description = "Definiciones de esta pagina")
		List<MedicionDefinicionResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de definiciones que cumplen el filtro", example = "18")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "1")
		int totalPages) {

	/**
	 * Recorta la lista completa a la pagina pedida.
	 *
	 * <p>Una pagina fuera de rango devuelve contenido vacio, no un error: pedir la pagina 10 de un
	 * listado de 3 elementos no es una solicitud invalida, es un listado que se quedo corto.
	 */
	public static MedicionDefinicionPageResponse of(
			List<MedicionDefinicionView> todas, int page, int size) {

		int desde = Math.min(page * size, todas.size());
		int hasta = Math.min(desde + size, todas.size());
		List<MedicionDefinicionResponse> contenido = todas.subList(desde, hasta).stream()
				.map(MedicionDefinicionResponse::from)
				.toList();
		int totalPaginas = (int) Math.ceil((double) todas.size() / size);
		return new MedicionDefinicionPageResponse(
				contenido, page, size, todas.size(), totalPaginas);
	}
}
