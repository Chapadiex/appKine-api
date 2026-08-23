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
}
