package com.akine.encounter.domain.exception;

/**
 * El Caso al que se quiere colgar la atencion no sirve para eso (04.03).
 *
 * <h2>Por que la excepcion vive en {@code encounter} y no en {@code clinical}</h2>
 *
 * <p>Porque quien rechaza es este modulo. {@code clinical} responde por su {@code spi} —el caso
 * existe, es de tal historia, esta activo— y <b>no autoriza nada ni lanza nada</b>; la decision de
 * que esa combinacion no habilita una sesion es de quien registra la sesion. Importar la excepcion
 * de {@code clinical.domain} ademas rompe ArchUnit: entre modulos solo se alcanza el {@code spi}.
 *
 * <p>Lo que si se comparte es el {@code problemType}, que vive en {@code platform.spi.problem} y
 * es el catalogo unico de la API: el cliente recibe {@code caso-clinico-no-accesible} o
 * {@code caso-clinico-cerrado} venga de donde venga, que es lo que le permite manejar una sola
 * respuesta por situacion.
 *
 * <h2>Los tres motivos, y por que no colapsan en uno</h2>
 *
 * <p>{@link Motivo#NO_ACCESIBLE} y {@link Motivo#DE_OTRA_HISTORIA} son <b>404</b> y son
 * indistinguibles desde afuera a proposito: distinguirlas permitiria censar por ids los casos de
 * otro paciente o de otro centro. {@link Motivo#CERRADO} es <b>409</b>, y tiene que ser otra cosa
 * porque lleva a otra accion: el caso existe, es del paciente, y lo que corresponde es reabrirlo
 * con motivo — no buscar otro.
 */
public class CasoNoAsignableException extends RuntimeException {

	/** Por que ese caso no habilita esta atencion. */
	public enum Motivo {
		/** No existe, o es de otra organizacion. */
		NO_ACCESIBLE,
		/** Existe, pero cuelga de la historia clinica de otro paciente. */
		DE_OTRA_HISTORIA,
		/** Existe y es del paciente, pero esta cerrado: no admite sesiones nuevas. */
		CERRADO
	}

	private final long casoId;
	private final Motivo motivo;

	public CasoNoAsignableException(long casoId, Motivo motivo) {
		super("El caso clinico " + casoId + " no admite esta atencion: " + motivo);
		this.casoId = casoId;
		this.motivo = motivo;
	}

	public long getCasoId() {
		return casoId;
	}

	public Motivo getMotivo() {
		return motivo;
	}
}
