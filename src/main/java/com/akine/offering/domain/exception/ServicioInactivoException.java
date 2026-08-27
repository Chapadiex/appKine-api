package com.akine.offering.domain.exception;

/**
 * Se intento crear una {@code Oferta} nueva sobre un {@code Servicio} global dado de baja (409).
 *
 * <p>No es un CHECK de la base: la cabecera de la migracion V24 lo dice explicitamente — la FK
 * {@code fk_oferta_servicio} es {@code RESTRICT} sin clausula {@code ON DELETE}, asi que el
 * borrado FISICO de un servicio referenciado es imposible desde la base, pero la base no puede
 * expresar "solo al insertar". Esa comprobacion —bloquear ALTAS nuevas sobre un servicio
 * inactivo— la hace la aplicacion (RF-M27-002).
 *
 * <p><b>La baja de un servicio NO cascadea.</b> RN-M03-006 exige no afectar historicos: las
 * ofertas VIGENTES que ya referencian el servicio siguen operando sin cambios. Esta excepcion
 * solo se lanza en el camino de ALTA de una oferta nueva, nunca al leer o al editar una
 * existente.
 *
 * <p>409 y no 404: el servicio existe y es visible (es global), pero su estado no admite la
 * operacion pedida.
 */
public class ServicioInactivoException extends RuntimeException {

	private final long servicioId;

	public ServicioInactivoException(long servicioId) {
		super("El servicio " + servicioId + " esta dado de baja: no admite ofertas nuevas");
		this.servicioId = servicioId;
	}

	public long getServicioId() {
		return servicioId;
	}
}
