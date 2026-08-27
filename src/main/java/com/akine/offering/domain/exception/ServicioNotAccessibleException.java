package com.akine.offering.domain.exception;

/**
 * El {@code Servicio} pedido no existe (404).
 *
 * <p><b>Aca 404 significa literalmente "no existe", y no es el mismo 404 que el resto del
 * producto.</b> En {@code espacio}, {@code oferta} o el catalogo clinico contextual el 404
 * encubre tambien "existe pero es de otro tenant", porque un 403 confirmaria la existencia de la
 * fila y bastaria recorrer ids consecutivos para inventariar los datos de otro centro (ADR-0018).
 * {@code servicio} es GLOBAL —una sola poblacion, sin {@code organization_id}, ADR-0023—: no hay
 * ninguna fila ajena que ocultar, todo usuario autenticado ve el catalogo entero, y por lo tanto
 * el unico motivo por el que un id no resuelve es que nadie lo creo o que el numero esta mal.
 *
 * <p>Se lanza al EDITAR o al DAR DE BAJA un id inexistente. Un servicio INACTIVO <b>no</b> lanza
 * esta excepcion: se lee con 200 y conserva su nombre y su codigo (RN-M03-006, no afectar
 * historicos). Lo que un servicio inactivo rechaza son las operaciones nuevas, y eso es un 409
 * — ver {@link ServicioInactivoException}.
 */
public class ServicioNotAccessibleException extends RuntimeException {

	private final long servicioId;

	public ServicioNotAccessibleException(long servicioId) {
		super("No existe un servicio con id " + servicioId + " en el catalogo global");
		this.servicioId = servicioId;
	}

	public long getServicioId() {
		return servicioId;
	}
}
