package com.akine.identity.infrastructure;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.EmailNormalizado;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.spi.AccountDirectory;
import com.akine.identity.spi.AccountSnapshot;
import com.akine.identity.spi.AccountState;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implementacion del contrato de lectura {@link AccountDirectory}.
 *
 * <p>Vive en {@code infrastructure} y no en {@code application} porque no decide nada: traduce
 * una fila a un record y devuelve. Meterlo en un servicio daria a entender que hay una regla de
 * negocio ahi, y no la hay.
 *
 * <p><b>Jamas devuelve la entity.</b> Solo los records del {@code spi}: si {@code Cuenta}
 * saliera del modulo, otro modulo podria modificarla y guardarla, y {@code identity} dejaria de
 * ser el propietario de su tabla. Es la misma razon por la que
 * {@code OrganizationMembershipDirectory} devuelve {@code TenantMembership} y no
 * {@code Membership}.
 *
 * <p>{@code readOnly = true} en los tres metodos: este contrato no escribe y no puede empezar a
 * hacerlo por descuido.
 */
@Component
public class CuentaDirectoryAdapter implements AccountDirectory {

	private final CuentaRepository cuentaRepository;

	public CuentaDirectoryAdapter(CuentaRepository cuentaRepository) {
		this.cuentaRepository = cuentaRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean existeCuentaCon(String email) {
		return buscarPorEmail(email).isPresent();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccountSnapshot> cuenta(long accountId) {
		return cuentaRepository.findById(accountId).map(CuentaDirectoryAdapter::fotoDe);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccountSnapshot> cuentaCon(String email) {
		return buscarPorEmail(email).map(CuentaDirectoryAdapter::fotoDe);
	}

	/**
	 * Busca tolerando un email vacio o mal escrito.
	 *
	 * <p>Un email invalido no es un error del servidor: es una direccion que no tiene cuenta, y
	 * tiene que salir por el mismo camino que una que no existe.
	 */
	private Optional<Cuenta> buscarPorEmail(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		return cuentaRepository.findByEmailNormalizado(EmailNormalizado.of(email));
	}

	private static AccountSnapshot fotoDe(Cuenta cuenta) {
		return new AccountSnapshot(
				cuenta.getId(),
				cuenta.getEmail(),
				(cuenta.getNombre() + " " + cuenta.getApellido()).strip(),
				traducir(cuenta.getEstado()),
				cuenta.isActive());
	}

	/**
	 * Traduce el estado del dominio al del {@code spi}.
	 *
	 * <p>Exhaustivo a proposito: si {@code identity} agrega un estado y nadie lo mapea, esto
	 * deja de compilar. Un {@code valueOf} por nombre fallaria recien en produccion y sobre un
	 * dato del que dependen decisiones de acceso.
	 */
	private static AccountState traducir(EstadoCuenta estado) {
		return switch (estado) {
			case PENDIENTE_ACTIVACION -> AccountState.PENDIENTE_ACTIVACION;
			case ACTIVA -> AccountState.ACTIVA;
			case BLOQUEADA -> AccountState.BLOQUEADA;
			case DESACTIVADA -> AccountState.DESACTIVADA;
		};
	}
}
