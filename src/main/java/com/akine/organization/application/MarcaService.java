package com.akine.organization.application;

import com.akine.organization.domain.LogoResumen;
import com.akine.organization.domain.ColoresDeInterfaz;
import com.akine.organization.domain.LogoVariante;
import com.akine.organization.domain.OrganizationMarca;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.port.OrganizationLogoRepositoryPort;
import com.akine.organization.domain.port.OrganizationMarcaRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Marca del tenant: colores institucionales por tema, datos de contacto del pie de pagina y
 * logo.
 *
 * <p><b>La fila se crea en la primera edicion</b>, con un {@code INSERT ... ON CONFLICT DO
 * NOTHING}: leer la marca de un centro que nunca la toco devuelve todo vacio con version 0, sin
 * escribir nada.
 *
 * <p><b>El contraste no se valida</b>: la decision del producto es que el centro edita libre y
 * la pantalla avisa. Lo que si se valida es la forma de cada dato.
 *
 * <p><b>El tipo del logo lo deciden sus bytes</b>, no el {@code Content-Type} del pedido (regla
 * de 03.02). SVG queda afuera a proposito: es un documento que puede traer script.
 */
@Service
public class MarcaService {

	/** Tope del logo. Es una imagen de cabecera, no una foto. */
	static final int LOGO_MAX_BYTES = 500 * 1024;

	private final OrganizationRepositoryPort organizationRepository;
	private final OrganizationMarcaRepositoryPort marcaRepository;
	private final OrganizationLogoRepositoryPort logoRepository;
	private final AuditTrail auditTrail;

	public MarcaService(
			OrganizationRepositoryPort organizationRepository,
			OrganizationMarcaRepositoryPort marcaRepository,
			OrganizationLogoRepositoryPort logoRepository,
			AuditTrail auditTrail) {
		this.organizationRepository = organizationRepository;
		this.marcaRepository = marcaRepository;
		this.logoRepository = logoRepository;
		this.auditTrail = auditTrail;
	}

	@Transactional(readOnly = true)
	public MarcaView ver(long organizationId) {
		exigirVigente(organizationId);
		return vista(organizationId, marcaRepository.findByOrganizationId(organizationId));
	}

	/**
	 * @throws OptimisticLockingFailureException la version quedo vieja (409 concurrent-modification)
	 */
	@Transactional
	public MarcaView actualizar(
			long organizationId, MarcaCommand comando, long versionEsperada, Long actorAccountId) {
		exigirVigente(organizationId);
		marcaRepository.asegurarFila(organizationId);
		OrganizationMarca marca = marcaRepository.findByOrganizationId(organizationId)
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
		if (marca.getVersion() != versionEsperada) {
			throw new OptimisticLockingFailureException("La marca fue modificada por otra operacion");
		}

		marca.reemplazar(
				comando.colorPrincipalClaro(), comando.colorAcentoClaro(),
				comando.colorPrincipalOscuro(), comando.colorAcentoOscuro(),
				comando.interfazClaro(), comando.interfazOscuro(),
				comando.telefono(), comando.email(), comando.sitioWeb(),
				comando.direccion(), comando.leyenda());
		OrganizationMarca guardada = marcaRepository.saveAndFlush(marca);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("colorPrincipalClaro", String.valueOf(guardada.getColorPrincipalClaro()));
		detalles.put("colorAcentoClaro", String.valueOf(guardada.getColorAcentoClaro()));
		detalles.put("colorPrincipalOscuro", String.valueOf(guardada.getColorPrincipalOscuro()));
		detalles.put("colorAcentoOscuro", String.valueOf(guardada.getColorAcentoOscuro()));
		detalles.put("interfazClaro", String.valueOf(guardada.getInterfazClaro()));
		detalles.put("interfazOscuro", String.valueOf(guardada.getInterfazOscuro()));
		auditar(organizationId, actorAccountId, AuditEvents.ORGANIZATION_BRANDING_UPDATED, detalles);

		return vista(organizationId, Optional.of(guardada));
	}

	/**
	 * Sube o reemplaza el logo de un tema.
	 *
	 * @throws CampoInvalidoException vacio, mas grande que el tope o de un formato no admitido (400)
	 */
	@Transactional
	public MarcaView subirLogo(
			long organizationId, LogoVariante variante, byte[] contenido, Long actorAccountId) {
		exigirVigente(organizationId);
		if (contenido == null || contenido.length == 0) {
			throw new CampoInvalidoException("logo", "El archivo del logo esta vacio");
		}
		if (contenido.length > LOGO_MAX_BYTES) {
			throw new CampoInvalidoException("logo",
					"El logo no puede superar los " + (LOGO_MAX_BYTES / 1024) + " kB");
		}
		String tipo = tipoPorContenido(contenido);
		if (tipo == null) {
			throw new CampoInvalidoException("logo", "El logo tiene que ser una imagen PNG, JPG o WebP");
		}
		String huella = sha256(contenido);
		logoRepository.guardar(organizationId, variante.name(), tipo, contenido, contenido.length, huella);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("variante", variante.name());
		detalles.put("contentType", tipo);
		detalles.put("sha256", huella);
		auditar(organizationId, actorAccountId, AuditEvents.ORGANIZATION_LOGO_UPDATED, detalles);

		return vista(organizationId, marcaRepository.findByOrganizationId(organizationId));
	}

	/** Quita el logo de un tema. Idempotente: quitar el que no esta no es un error. */
	@Transactional
	public MarcaView quitarLogo(long organizationId, LogoVariante variante, Long actorAccountId) {
		exigirVigente(organizationId);
		if (logoRepository.eliminar(organizationId, variante.name()) > 0) {
			auditar(organizationId, actorAccountId, AuditEvents.ORGANIZATION_LOGO_REMOVED,
					Map.of("variante", variante.name()));
		}
		return vista(organizationId, marcaRepository.findByOrganizationId(organizationId));
	}

	/**
	 * @throws OrganizationNotFoundException si el centro no tiene logo para ese tema (404)
	 */
	@Transactional(readOnly = true)
	public LogoContenido logo(long organizationId, LogoVariante variante) {
		exigirVigente(organizationId);
		return logoRepository.findByOrganizationIdAndVariante(organizationId, variante)
				.map(l -> new LogoContenido(l.getContentType(), l.getContenido(), l.getSha256()))
				.orElseThrow(() -> new OrganizationNotFoundException(organizationId));
	}

	/**
	 * PNG, JPEG o WebP segun la firma del archivo; {@code null} si no es ninguno. Lo que declare
	 * el cliente no cuenta: un HTML rotulado como imagen no pasa de aca.
	 */
	static String tipoPorContenido(byte[] b) {
		if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
				&& b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
			return "image/png";
		}
		if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (b.length >= 12
				&& "RIFF".equals(new String(b, 0, 4, StandardCharsets.US_ASCII))
				&& "WEBP".equals(new String(b, 8, 4, StandardCharsets.US_ASCII))) {
			return "image/webp";
		}
		return null;
	}

	private void exigirVigente(long organizationId) {
		if (organizationRepository.findByIdAndActiveTrue(organizationId).isEmpty()) {
			throw new OrganizationNotFoundException(organizationId);
		}
	}

	private MarcaView vista(long organizationId, Optional<OrganizationMarca> marca) {
		List<LogoResumen> logos = logoRepository.resumenes(organizationId);
		MarcaView.Logo claro = logoDe(logos, LogoVariante.CLARO);
		MarcaView.Logo oscuro = logoDe(logos, LogoVariante.OSCURO);
		return marca
				.map(m -> new MarcaView(organizationId, m.getVersion(),
						m.getColorPrincipalClaro(), m.getColorAcentoClaro(),
						m.getColorPrincipalOscuro(), m.getColorAcentoOscuro(),
						m.getInterfazClaro(), m.getInterfazOscuro(),
						m.getTelefono(), m.getEmail(), m.getSitioWeb(), m.getDireccion(),
						m.getLeyenda(), claro, oscuro))
				.orElseGet(() -> new MarcaView(organizationId, 0,
						null, null, null, null, ColoresDeInterfaz.NINGUNO, ColoresDeInterfaz.NINGUNO,
						null, null, null, null, null, claro, oscuro));
	}

	private static MarcaView.Logo logoDe(List<LogoResumen> logos, LogoVariante variante) {
		return logos.stream()
				.filter(l -> l.getVariante() == variante)
				.findFirst()
				.map(l -> new MarcaView.Logo(l.getContentType(), l.getSha256()))
				.orElse(null);
	}

	private void auditar(long organizationId, Long actorAccountId, String evento, Map<String, String> detalles) {
		auditTrail.record(new AuditEntry(
				organizationId, null, actorAccountId, evento, AuditEvents.ENTITY_ORGANIZATION,
				organizationId, null, null, detalles, null, AuditEvents.correlationId(), Instant.now()));
	}

	private static String sha256(byte[] contenido) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 no disponible", e);
		}
	}
}
