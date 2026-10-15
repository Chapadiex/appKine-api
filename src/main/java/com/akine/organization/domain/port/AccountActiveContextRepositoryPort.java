package com.akine.organization.domain.port;

import com.akine.organization.domain.AccountActiveContext;

import java.util.Optional;

/**
 * Acceso al puntero de contexto activo de una cuenta.
 *
 * <p>Hay a lo sumo una fila por cuenta, asi que la seleccion es un upsert: leer, y crear o
 * reapuntar. Un PUT repetido con el mismo valor no duplica filas.
 *
 * <p>Lo que devuelve es una PREFERENCIA, no una autorizacion. Quien la lea DEBE revalidarla:
 * el puntero puede haber quedado apuntando a una membership revocada o a una organizacion
 * cancelada, y en ese caso se trata como sin seleccion previa.
 */
public interface AccountActiveContextRepositoryPort {

	AccountActiveContext save(AccountActiveContext context);

	Optional<AccountActiveContext> findByAccountId(Long accountId);

	/**
	 * Crea o reapunta el puntero de la cuenta en una sola sentencia.
	 *
	 * <p>Leer y despues crear deja una carrera: dos selecciones simultaneas de una cuenta sin
	 * puntero insertan las dos y la segunda choca contra {@code uk_active_context_account}, con
	 * 500 en el request que iba a entregar el token. La sentencia unica no tiene ventana: el
	 * segundo espera el lock de la fila y la reapunta.
	 */
	void apuntar(long accountId, long organizationId, long consultorioId);
}
