package com.akine.offering.domain.exception;

/**
 * La sede sobre la que se pidio operar no existe, o es de otro tenant (404).
 *
 * <h2>Por que 404 y no 403, que es lo unico importante de esta clase</h2>
 *
 * <p>Un 403 confirmaria que esa organizacion y esa sede existen, y bastaria recorrer ids
 * consecutivos para inventariar el SaaS (ADR-0018). Es la regla que 01.01 dejo fijada y que esta
 * etapa hereda sin excepcion: <b>cross-tenant sale 404, nunca 403</b>. La unica salida con 403 de
 * este modulo es la FALTA DE CONTEXTO —el actor todavia no eligio donde trabaja—, que no filtra
 * nada y que el frontend necesita poder traducir a "elegi un consultorio"; y jamas 401, porque el
 * interceptor del frontend borra el token ante cualquier 401 y deja al usuario en un bucle de
 * login del que no sale.
 *
 * <h2>Por que este modulo declara la suya en vez de reusar la de {@code resource}</h2>
 *
 * <p>{@code resource.domain.exception.ConsultorioNotAccessibleException} dice exactamente lo
 * mismo, pero vive en el {@code domain} de otro modulo y ArchUnit prohibe importarlo
 * ({@code modulos_solo_se_alcanzan_por_su_spi}). Promoverla a un {@code spi} tampoco corresponde:
 * no es un contrato que {@code organization} ofrezca, es la forma en que cada modulo traduce a
 * HTTP el resultado de su propia comprobacion. La duplicacion es el precio explicito de la regla
 * de modulos, igual que {@link com.akine.offering.application.OperatingActor} lo documenta para el
 * actor.
 *
 * <p><b>El nombre es {@code NoAccesible} y no {@code NotAccessible} a proposito</b>: dos clases
 * homonimas en modulos distintos se confunden en cualquier import y en cualquier stack trace, y
 * el advice de este modulo tiene que mapear la suya y no la otra.
 */
public class ConsultorioNoAccesibleException extends RuntimeException {

	private final long consultorioId;

	public ConsultorioNoAccesibleException(long consultorioId) {
		super("Sede no accesible: " + consultorioId);
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
