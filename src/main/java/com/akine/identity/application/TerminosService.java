package com.akine.identity.application;

import com.akine.identity.domain.AceptacionTerminos;
import com.akine.identity.domain.TerminosSettings;
import com.akine.identity.domain.exception.TerminosNoAceptadosException;
import com.akine.identity.domain.port.AceptacionTerminosRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exigencia y constancia de la aceptacion de terminos en el alta (DP-45).
 *
 * <p>Dos usuarios: el registro self-service y la aceptacion de invitacion que crea cuenta. Los
 * dos llaman a {@link #exigirAceptacion} ANTES de mirar si el email ya tiene cuenta, para que el
 * 400 no distinga nada (ADR-0018), y a {@link #registrar} solo cuando efectivamente nace una
 * cuenta: en el camino del duplicado no hubo hecho sobre ninguna.
 */
@Service
public class TerminosService {

	static final String CAMPO_ACEPTA = "aceptaTerminos";
	static final String CAMPO_VERSION = "versionTerminos";

	private final TerminosSettings settings;
	private final AceptacionTerminosRepositoryPort repository;
	/** Cuenta → version vigente que ya acepto. Ver {@link #pendiente(long)}. */
	private final Map<Long, String> aceptadas = new ConcurrentHashMap<>();

	public TerminosService(TerminosSettings settings, AceptacionTerminosRepositoryPort repository) {
		this.settings = settings;
		this.repository = repository;
	}

	/** Version y enlace vigentes, para que el frontend los muestre. */
	public TerminosSettings vigentes() {
		return settings;
	}

	/**
	 * Valida la aceptacion contra la version vigente.
	 *
	 * @return la version aceptada, que hay que registrar con la cuenta, o {@code null} si no hay
	 *         version configurada (entonces no se exige ni se registra nada, como antes de DP-45)
	 * @throws TerminosNoAceptadosException si hay version vigente y no se acepto esa exacta
	 */
	public String exigirAceptacion(Boolean aceptaTerminos, String versionTerminos) {
		if (!settings.exigeAceptacion()) {
			return null;
		}
		if (!Boolean.TRUE.equals(aceptaTerminos)) {
			throw new TerminosNoAceptadosException(CAMPO_ACEPTA,
					"Hay que aceptar los terminos y condiciones para crear la cuenta");
		}
		String vigente = settings.versionVigente();
		if (versionTerminos == null || !vigente.equals(versionTerminos.strip())) {
			// La version vigente es publica (GET /api/v1/terms): nombrarla no revela nada y le
			// dice al cliente exactamente que corregir, que suele ser recargar el formulario.
			throw new TerminosNoAceptadosException(CAMPO_VERSION,
					"Los terminos aceptados no son los vigentes (version " + vigente
							+ "). Vuelva a leerlos y acepte la version actual");
		}
		return vigente;
	}

	/** Deja constancia de la aceptacion. No hace nada si {@code version} es {@code null}. */
	public void registrar(long cuentaId, String version, AceptacionTerminos.Origen origen,
			Instant ahora) {
		if (version == null) {
			return;
		}
		repository.save(new AceptacionTerminos(cuentaId, version, ahora, origen));
	}

	/** Lo que la cuenta ve de los terminos vigentes. Sin version configurada, nada pendiente. */
	public record Estado(String versionVigente, String url, boolean pendiente, Instant aceptadaEn) {
	}

	public Estado estado(long cuentaId) {
		String vigente = settings.versionVigente();
		if (vigente == null) {
			return new Estado(null, settings.url(), false, null);
		}
		Instant aceptadaEn = repository.findByCuentaIdAndVersion(cuentaId, vigente)
				.map(AceptacionTerminos::getAceptadoEn)
				.orElse(null);
		return new Estado(vigente, settings.url(), aceptadaEn == null, aceptadaEn);
	}

	/**
	 * {@code true} si la cuenta todavia no acepto la version vigente (19/11/2026).
	 *
	 * <p>Corre en cada pedido autenticado, asi que recuerda en memoria las cuentas que ya
	 * aceptaron. Es seguro porque una aceptacion no se borra nunca, y se guarda junto con la
	 * version: si la vigente cambia, la entrada vieja deja de coincidir y se vuelve a preguntar.
	 */
	public boolean pendiente(long cuentaId) {
		String vigente = settings.versionVigente();
		if (vigente == null || vigente.equals(aceptadas.get(cuentaId))) {
			return false;
		}
		if (repository.existsByCuentaIdAndVersion(cuentaId, vigente)) {
			aceptadas.put(cuentaId, vigente);
			return false;
		}
		return true;
	}

	/** La version vigente, o {@code null} si el despliegue no exige terminos. */
	public String versionVigente() {
		return settings.versionVigente();
	}

	/**
	 * Una cuenta que ya existia acepta la version vigente. Idempotente: si ya la habia aceptado
	 * no hace nada.
	 *
	 * <p>Sin {@code @Transactional} a proposito: el {@code save} corre en su propia transaccion,
	 * asi que si dos pedidos de la misma cuenta chocan contra
	 * {@code uk_aceptacion_terminos_cuenta_version}, el perdedor ve la excepcion sin dejar una
	 * transaccion marcada para rollback, y lo trata como lo que es: ya estaba aceptada.
	 *
	 * @throws TerminosNoAceptadosException si la version no es la vigente
	 */
	public void aceptar(long cuentaId, String versionTerminos, Instant ahora) {
		String vigente = settings.versionVigente();
		if (vigente == null) {
			return;
		}
		if (versionTerminos == null || !vigente.equals(versionTerminos.strip())) {
			throw new TerminosNoAceptadosException(CAMPO_VERSION,
					"Los terminos aceptados no son los vigentes (version " + vigente
							+ "). Vuelva a leerlos y acepte la version actual");
		}
		if (!repository.existsByCuentaIdAndVersion(cuentaId, vigente)) {
			try {
				repository.save(new AceptacionTerminos(
						cuentaId, vigente, ahora, AceptacionTerminos.Origen.REACEPTACION));
			} catch (DataIntegrityViolationException yaAceptada) {
				// La misma cuenta acepto en otro pedido al mismo tiempo: queda la primera fila.
			}
		}
		aceptadas.put(cuentaId, vigente);
	}
}
