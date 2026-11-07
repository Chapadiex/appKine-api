package com.akine.identity.application;

import java.util.List;

/** Foto de los datos cargados por el administrador antes de emitir una invitacion. */
public record DatosAltaManual(
		String nombre,
		String apellido,
		String tipoDocumento,
		String numeroDocumento,
		String telefono,
		String profesion,
		String matricula,
		String jurisdiccion,
		String especialidadPrincipal,
		List<String> especialidadesAdicionales,
		List<Long> sedeIds,
		List<ServicioElegido> servicios,
		String fechaIncorporacion,
		boolean recibeTurnos,
		boolean instructorDeActividades,
		boolean activo) {

	public record ServicioElegido(long sedeId, long ofertaId) {
	}

	public PerfilDeInvitacion perfil() {
		return new PerfilDeInvitacion(nombre, apellido, tipoDocumento, numeroDocumento, telefono,
				profesion, matricula, jurisdiccion, especialidadPrincipal, especialidadesAdicionales);
	}

	public DatosAltaManual conPerfil(PerfilDeInvitacion editado) {
		String nombreFinal = preferir(editado.nombre(), nombre);
		String apellidoFinal = preferir(editado.apellido(), apellido);
		if (nombreFinal.isBlank() || apellidoFinal.isBlank()) {
			throw new IllegalArgumentException("El nombre y el apellido son obligatorios");
		}
		return new DatosAltaManual(nombreFinal, apellidoFinal,
				preferir(editado.tipoDocumento(), tipoDocumento),
				preferir(editado.numeroDocumento(), numeroDocumento),
				preferir(editado.telefono(), telefono), preferir(editado.profesion(), profesion),
				preferir(editado.matricula(), matricula), preferir(editado.jurisdiccion(), jurisdiccion),
				preferir(editado.especialidadPrincipal(), especialidadPrincipal),
				editado.especialidadesAdicionales() == null
					? especialidadesAdicionales : List.copyOf(editado.especialidadesAdicionales()),
				sedeIds, servicios, fechaIncorporacion, recibeTurnos, instructorDeActividades, activo);
	}

	private static String preferir(String nuevo, String anterior) {
		return nuevo == null ? anterior : nuevo.strip();
	}
}
