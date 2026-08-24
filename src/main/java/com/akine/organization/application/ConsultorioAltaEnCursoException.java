package com.akine.organization.application;

/**
 * Otro hilo esta ejecutando AHORA MISMO un alta de sede con la misma clave de idempotencia.
 *
 * <p>Sale de la violacion de {@code uk_consultorio_alta_key} entre la lectura previa del
 * registro y su insercion. No es lo mismo que {@link IdempotencyKeyConflictException}, que es
 * la misma clave con otro contenido —un error del cliente—: aca el contenido puede ser
 * identico y el desenlace correcto es reintentar, porque el ganador ya esta creando la sede.
 *
 * <p><b>Por que no se resuelve haciendo replay aca mismo.</b> Porque el flush ya fallo, y
 * despues de un flush fallido la especificacion de JPA prohibe seguir usando el
 * {@code EntityManager}: releer la fila del ganador en esa sesion produce un
 * {@code AssertionFailure} o un "Transaction marked as rollbackOnly", o sea un 500 donde el
 * contrato promete otra cosa. Es la leccion que costo un 500 en 01.02 y esta documentada en
 * {@code OnboardingService}. El unico movimiento legal es revertir y que el cliente reintente
 * en una sesion limpia, donde la lectura previa ya encuentra el registro y hace el replay.
 *
 * <p>409, no 500: el cliente no hizo nada mal, llego segundo a su propio doble click.
 */
public class ConsultorioAltaEnCursoException extends RuntimeException {

	private final transient String idempotencyKey;

	public ConsultorioAltaEnCursoException(String idempotencyKey) {
		super("Otra solicitud con la misma clave de idempotencia esta en curso");
		this.idempotencyKey = idempotencyKey;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}
}
