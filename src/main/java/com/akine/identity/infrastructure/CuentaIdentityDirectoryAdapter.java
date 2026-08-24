package com.akine.identity.infrastructure;

import com.akine.identity.domain.Cuenta;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementacion del puerto invertido {@link AccountIdentityDirectory}.
 *
 * <p>{@code identity} es el propietario de la tabla {@code cuenta}, asi que es el unico que
 * puede implementarlo. El puerto vive en {@code platform.spi} para que {@code organization}
 * pueda consumirlo sin importar a {@code identity}: ver el javadoc del puerto.
 *
 * <p>Vive en {@code infrastructure} y no en {@code application} por el mismo motivo que
 * {@link CuentaDirectoryAdapter}: no decide nada, traduce filas a records.
 *
 * <p><b>Jamas devuelve la entity</b>, y no expone ni el estado ni la credencial: el contrato
 * responde quien es la cuenta, no si puede entrar.
 */
@Component
public class CuentaIdentityDirectoryAdapter implements AccountIdentityDirectory {

	private final CuentaRepository cuentaRepository;

	public CuentaIdentityDirectoryAdapter(CuentaRepository cuentaRepository) {
		this.cuentaRepository = cuentaRepository;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>Una sola consulta.</b> Los ids se deduplican y salen en un unico {@code IN} por
	 * {@code findAllById}: una pagina de 100 colaboradores cuesta dos consultas en total, no
	 * ciento una. La coleccion vacia ni siquiera toca la base.
	 */
	@Override
	@Transactional(readOnly = true)
	public Map<Long, AccountIdentity> identidadesDe(Collection<Long> accountIds) {
		if (accountIds == null || accountIds.isEmpty()) {
			return Map.of();
		}
		Set<Long> unicos = accountIds.stream()
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (unicos.isEmpty()) {
			return Map.of();
		}
		return cuentaRepository.findAllById(unicos).stream()
				.map(CuentaIdentityDirectoryAdapter::identidadDe)
				.collect(Collectors.toMap(AccountIdentity::accountId, Function.identity()));
	}

	private static AccountIdentity identidadDe(Cuenta cuenta) {
		return new AccountIdentity(
				cuenta.getId(),
				(cuenta.getNombre() + " " + cuenta.getApellido()).strip(),
				cuenta.getEmail());
	}
}
