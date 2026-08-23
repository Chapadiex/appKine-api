package com.akine.identity.spi;

/**
 * Estado de una cuenta, tal como lo ven los demas modulos.
 *
 * <p>Es un enum propio del {@code spi} y no {@code identity.domain.EstadoCuenta}, por el mismo
 * motivo por el que {@code MembershipSnapshot} publica el rol como {@code String}: un consumidor
 * que usara el enum del dominio estaria importando un paquete privado del modulo, y ArchUnit lo
 * rechaza. La traduccion es explicita y exhaustiva del lado de identity, asi que agregar un
 * estado sin mapearlo no compila.
 *
 * <p>Ninguno de estos valores es una autorizacion. Que una cuenta este {@link #ACTIVA} dice que
 * puede autenticarse, no que pueda hacer algo en particular: eso lo decide la membership
 * vigente, revalidada en cada request.
 */
public enum AccountState {

	/** Existe, pero todavia no confirmo el enlace que se le envio. */
	PENDIENTE_ACTIVACION,

	/** Unico estado que habilita el login. */
	ACTIVA,

	/** Suspension administrativa reversible. */
	BLOQUEADA,

	/** Baja logica definitiva. La fila se conserva; el acceso no vuelve. */
	DESACTIVADA
}
