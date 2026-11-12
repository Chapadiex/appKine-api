package com.akine.organization;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.application.CampoInvalidoException;
import com.akine.organization.application.LogoContenido;
import com.akine.organization.application.MarcaCommand;
import com.akine.organization.application.MarcaService;
import com.akine.organization.application.MarcaView;
import com.akine.organization.domain.LogoVariante;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La marca del centro contra PostgreSQL real: la fila que se crea en la primera edicion, el
 * upsert nativo del logo y los CHECK de V14. Son consultas nativas: un mock no las prueba.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class MarcaDeOrganizacionIT {

	private static final byte[] PNG = {
			(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
	private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 9};
	private static final byte[] WEBP = "RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);

	@Autowired private MarcaService marca;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("un centro que nunca edito su marca responde todo vacio con version 0 y no escribe")
	void sin_marca_devuelve_vacio() {
		long org = insertarOrganizacion();

		MarcaView vista = marca.ver(org);

		assertThat(vista.version()).isZero();
		assertThat(vista.colorPrincipalClaro()).isNull();
		assertThat(vista.logoClaro()).isNull();
		assertThat(filasDeMarca(org)).as("leer no crea la fila").isZero();
	}

	@Test
	@DisplayName("la primera edicion crea la fila, normaliza y devuelve la version ya incrementada")
	void primera_edicion_crea_y_versiona() {
		long org = insertarOrganizacion();

		MarcaView guardada = marca.actualizar(org, new MarcaCommand(
				"#0F766E", "#115e59", "#2dd4bf", "",
				"  +54 351 555-0100 ", "contacto@centro.example", "https://centro.example",
				"Av. Colon 1234", "Lunes a viernes de 8 a 20 h"), 0, null);

		assertThat(guardada.version()).as("la respuesta trae la version nueva, no la leida").isEqualTo(1);
		assertThat(guardada.colorPrincipalClaro()).as("en minusculas").isEqualTo("#0f766e");
		assertThat(guardada.colorAcentoOscuro()).as("vacio es volver al de AKINE").isNull();
		assertThat(guardada.telefono()).isEqualTo("+54 351 555-0100");
		assertThat(jdbc.queryForObject(
				"SELECT color_principal_claro FROM organization_marca WHERE organization_id = ?",
				String.class, org)).isEqualTo("#0f766e");
		assertThat(eventos(org, "ORGANIZATION_BRANDING_UPDATED")).isEqualTo(1);

		// La segunda edicion compite por version: la vieja pierde y no pisa nada.
		assertThatThrownBy(() -> marca.actualizar(org, comandoVacio(), 0, null))
				.isInstanceOf(OptimisticLockingFailureException.class);
		MarcaView segunda = marca.actualizar(org, comandoVacio(), 1, null);
		assertThat(segunda.version()).isEqualTo(2);
		assertThat(segunda.colorPrincipalClaro()).as("reemplazo completo: lo omitido se borra").isNull();
		assertThat(marca.ver(org).email()).isNull();
	}

	@Test
	@DisplayName("subir un logo donde ya habia uno lo reemplaza; quitarlo lo borra y es idempotente")
	void logo_se_sube_reemplaza_y_quita() {
		long org = insertarOrganizacion();

		MarcaView conPng = marca.subirLogo(org, LogoVariante.CLARO, PNG, null);
		assertThat(conPng.logoClaro().contentType()).isEqualTo("image/png");
		assertThat(conPng.logoOscuro()).isNull();
		String huellaPng = conPng.logoClaro().sha256();

		MarcaView conJpeg = marca.subirLogo(org, LogoVariante.CLARO, JPEG, null);
		assertThat(conJpeg.logoClaro().contentType()).isEqualTo("image/jpeg");
		assertThat(conJpeg.logoClaro().sha256()).as("otra imagen, otra huella").isNotEqualTo(huellaPng);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM organization_logo WHERE organization_id = ?", Integer.class, org))
				.as("un solo logo por tema").isEqualTo(1);

		marca.subirLogo(org, LogoVariante.OSCURO, WEBP, null);
		LogoContenido oscuro = marca.logo(org, LogoVariante.OSCURO);
		assertThat(oscuro.contentType()).isEqualTo("image/webp");
		assertThat(oscuro.contenido()).isEqualTo(WEBP);
		assertThat(oscuro.sha256()).hasSize(64);

		assertThat(marca.quitarLogo(org, LogoVariante.CLARO, null).logoClaro()).isNull();
		assertThat(marca.quitarLogo(org, LogoVariante.CLARO, null).logoOscuro()).isNotNull();
		assertThatThrownBy(() -> marca.logo(org, LogoVariante.CLARO))
				.isInstanceOf(OrganizationNotFoundException.class);
		assertThat(eventos(org, "ORGANIZATION_LOGO_UPDATED")).isEqualTo(3);
		assertThat(eventos(org, "ORGANIZATION_LOGO_REMOVED"))
				.as("quitar lo que no esta no deja evento").isEqualTo(1);
	}

	@Test
	@DisplayName("el tipo lo deciden los bytes: SVG, vacio y demasiado grande se rechazan sin guardar")
	void logo_invalido_se_rechaza() {
		long org = insertarOrganizacion();
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
				.getBytes(StandardCharsets.UTF_8);
		byte[] enorme = new byte[500 * 1024 + 1];
		System.arraycopy(PNG, 0, enorme, 0, PNG.length);

		for (byte[] contenido : new byte[][] {svg, new byte[0], null, enorme}) {
			assertThatThrownBy(() -> marca.subirLogo(org, LogoVariante.CLARO, contenido, null))
					.isInstanceOf(CampoInvalidoException.class)
					.extracting(e -> ((CampoInvalidoException) e).getCampo())
					.isEqualTo("logo");
		}
		assertThat(marca.ver(org).logoClaro()).isNull();
	}

	@Test
	@DisplayName("una organizacion inexistente o dada de baja responde igual: no encontrada")
	void organizacion_no_vigente_es_no_encontrada() {
		long baja = insertarOrganizacion();
		jdbc.update("UPDATE organization SET active = false, deleted_at = CURRENT_TIMESTAMP WHERE id = ?", baja);
		long inexistente = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000 FROM organization", Long.class);

		for (long org : new long[] {baja, inexistente}) {
			assertThatThrownBy(() -> marca.ver(org)).isInstanceOf(OrganizationNotFoundException.class);
			assertThatThrownBy(() -> marca.actualizar(org, comandoVacio(), 0, null))
					.isInstanceOf(OrganizationNotFoundException.class);
			assertThatThrownBy(() -> marca.subirLogo(org, LogoVariante.CLARO, PNG, null))
					.isInstanceOf(OrganizationNotFoundException.class);
			assertThatThrownBy(() -> marca.quitarLogo(org, LogoVariante.CLARO, null))
					.isInstanceOf(OrganizationNotFoundException.class);
			assertThatThrownBy(() -> marca.logo(org, LogoVariante.CLARO))
					.isInstanceOf(OrganizationNotFoundException.class);
		}
	}

	@Test
	@DisplayName("la marca de un centro no se mezcla con la de otro")
	void aislamiento_entre_centros() {
		long a = insertarOrganizacion();
		long b = insertarOrganizacion();
		marca.actualizar(a, new MarcaCommand("#0f766e", null, null, null, null, null, null, null, null), 0, null);
		marca.subirLogo(a, LogoVariante.CLARO, PNG, null);

		MarcaView deB = marca.ver(b);
		assertThat(deB.colorPrincipalClaro()).isNull();
		assertThat(deB.logoClaro()).isNull();
	}

	// =================================================================================

	private static MarcaCommand comandoVacio() {
		return new MarcaCommand(null, null, null, null, null, null, null, null, null);
	}

	private int filasDeMarca(long org) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM organization_marca WHERE organization_id = ?", Integer.class, org);
	}

	private int eventos(long org, String tipo) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ?",
				Integer.class, org, tipo);
	}

	private long insertarOrganizacion() {
		String slug = "marca-it-" + UUID.randomUUID().toString().substring(0, 8);
		return jdbc.queryForObject("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				RETURNING id""", Long.class, "Centro sintetico " + slug, slug);
	}
}
