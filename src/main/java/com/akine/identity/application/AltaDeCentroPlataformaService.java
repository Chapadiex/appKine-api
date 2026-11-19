package com.akine.identity.application;

import com.akine.organization.spi.AltaDeCentro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta de un centro desde la plataforma (18/11/2026, docs/diseno/AKINE-landing-y-ofertas.md §6).
 *
 * <p>Vive en {@code identity} por la misma razon que el registro: {@code organization} no puede
 * importar {@code identity}, y el alta toca los dos. En una transaccion: el centro con su
 * contratacion (lado {@code organization}) y la invitacion al duenio. Si algo falla no queda
 * un centro sin duenio invitado ni una invitacion a un centro que no existe.
 */
@Service
public class AltaDeCentroPlataformaService {

	private final AltaDeCentro altaDeCentro;
	private final ColaboradorInvitacionService invitaciones;

	public AltaDeCentroPlataformaService(AltaDeCentro altaDeCentro, ColaboradorInvitacionService invitaciones) {
		this.altaDeCentro = altaDeCentro;
		this.invitaciones = invitaciones;
	}

	public record Resultado(long organizationId, long consultorioId, long invitacionId, String emailDuenio) {
	}

	@Transactional
	public Resultado darDeAlta(AltaDeCentro.Comando comando, String emailDuenio) {
		if (emailDuenio == null || emailDuenio.isBlank()) {
			throw new IllegalArgumentException("Indicá el correo del duenio");
		}
		altaDeCentro.validar(comando);
		AltaDeCentro.CentroProvisto centro = altaDeCentro.provisionar(comando);
		InvitacionView invitacion = invitaciones.invitarDuenioDesdePlataforma(
				centro.organizationId(), emailDuenio, comando.actorAccountId());
		return new Resultado(centro.organizationId(), centro.consultorioId(), invitacion.id(),
				invitacion.email());
	}
}
