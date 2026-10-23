package com.akine.identity.application;

import com.akine.identity.domain.AceptacionTerminos;
import com.akine.identity.domain.TerminosSettings;
import com.akine.identity.domain.exception.TerminosNoAceptadosException;
import com.akine.identity.domain.port.AceptacionTerminosRepositoryPort;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
}
