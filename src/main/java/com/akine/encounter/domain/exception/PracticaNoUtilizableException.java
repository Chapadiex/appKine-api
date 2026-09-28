package com.akine.encounter.domain.exception;

/**
 * La practica declarada no sirve para registrar esta intervencion.
 *
 * <p>Dos motivos con dos respuestas distintas, y la diferencia importa porque llevan a acciones
 * distintas:
 *
 * <ul>
 *   <li><b>{@code INEXISTENTE} = 404.</b> No existe, o es del catalogo de otro tenant.
 *       Indistinguibles a proposito: {@code CatalogoDirectory} ya devuelve vacio en los dos casos
 *       y distinguirlos permitiria censar el catalogo propio de otro centro.</li>
 *   <li><b>{@code NO_VIGENTE} = 409.</b> La practica existe y es visible, pero fue dada de baja o
 *       esta fuera de su ventana. La accion que corresponde es elegir otra, no buscar el id.</li>
 * </ul>
 *
 * <p><b>Se valida la vigencia al REGISTRAR y no la del dia de la atencion</b>, y es una decision:
 * quien esta cargando lo que acaba de hacer tiene que elegir de un selector vigente
 * ({@code CatalogoDirectory} distingue explicitamente el selector del historico). Lo ya guardado
 * sigue resolviendo aunque la practica se de de baja despues: para eso estan los snapshots de
 * codigo y nombre.
 */
public class PracticaNoUtilizableException extends RuntimeException {

	public enum Motivo {
		/** No existe o es de otro tenant. 404. */
		INEXISTENTE,
		/** Existe pero no se puede elegir hoy. 409. */
		NO_VIGENTE
	}

	private final long practicaId;
	private final Motivo motivo;

	public PracticaNoUtilizableException(long practicaId, Motivo motivo) {
		super("Practica no utilizable: " + practicaId + " (" + motivo + ")");
		this.practicaId = practicaId;
		this.motivo = motivo;
	}

	public long getPracticaId() {
		return practicaId;
	}

	public Motivo getMotivo() {
		return motivo;
	}
}
