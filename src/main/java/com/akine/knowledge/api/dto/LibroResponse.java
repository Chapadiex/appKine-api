package com.akine.knowledge.api.dto;

import com.akine.knowledge.domain.EstadoVisibleLibro;
import com.akine.knowledge.application.LibroView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un libro de la biblioteca de la organizacion")
public record LibroResponse(
		@Schema(example = "12") long id,
		@Schema(example = "Kinesiologia del deporte") String titulo,
		String autores,
		String edicion,
		String area,
		@Schema(description = "Paginas del PDF; vacio hasta que se procesa") Integer paginas,
		@Schema(description = "Estado que ve el usuario; INACTIVO si esta dado de baja")
		EstadoVisibleLibro estado,
		@Schema(description = "Avance del procesamiento, 0 a 100", example = "40") int progreso,
		@Schema(description = "Por que fallo el procesamiento, si fallo") String motivoError,
		@Schema(description = "Libro al que reemplaza, mientras el reemplazo no esta LISTO")
		Long reemplazaLibroId,
		@Schema(description = "Version para el control optimista de PUT") long version) {

	public static LibroResponse de(LibroView l) {
		return new LibroResponse(l.id(), l.titulo(), l.autores(), l.edicion(),
				l.area(), l.paginas(), l.estado(), l.progreso(), l.motivoError(),
				l.reemplazaLibroId(), l.version());
	}
}
