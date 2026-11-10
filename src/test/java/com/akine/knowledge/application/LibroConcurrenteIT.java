package com.akine.knowledge.application;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;

import com.akine.knowledge.domain.exception.LibroDuplicadoException;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Altas simultaneas del mismo PDF contra MySQL: una gana, la otra recibe 409, nunca 500. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"akine.archivos.base-dir=target/test-archivos",
		"akine.biblioteca.worker.enabled=false"})
class LibroConcurrenteIT {

	private static final OperatingActor ACTOR = new OperatingActor(99L, false, null);

	@Autowired LibroService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired BibliotecaVersionPort version;
	@MockitoBean BibliotecaAcceso acceso;

	long orgId;
	OperatingActor actor;

	@BeforeEach
	void organizacion() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "lib-it-" + sufijo);
		orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class,
				"lib-it-" + sufijo);
		actor = new OperatingActor(99L, false, orgId);
	}

	private static ArchivoSubido pdf(String cuerpo) {
		return new ArchivoSubido("a.pdf", ("%PDF-1.7 " + cuerpo).getBytes());
	}

	private static final DatosDeLibro DATOS = new DatosDeLibro("Titulo", null, null, null);

	@Test
	void altas_simultaneas_del_mismo_archivo_dan_una_ganadora_y_un_409() throws Exception {
		ExecutorService hilos = Executors.newFixedThreadPool(2);
		try {
			for (int ronda = 0; ronda < 15; ronda++) {
				ArchivoSubido archivo = pdf("ronda " + ronda + UUID.randomUUID());
				CyclicBarrier salida = new CyclicBarrier(2);
				Callable<Object> alta = () -> {
					salida.await();
					try {
						return service.subir(actor, orgId, DATOS, archivo, true);
					} catch (Exception e) {
						return e;
					}
				};
				Future<Object> a = hilos.submit(alta);
				Future<Object> b = hilos.submit(alta);
				List<Object> resultados = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));

				assertThat(resultados.stream().filter(LibroView.class::isInstance)).hasSize(1);
				assertThat(resultados.stream().filter(LibroDuplicadoException.class::isInstance))
						.as("el perdedor recibe libro-duplicado, no un 500: %s", resultados).hasSize(1);
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM libro WHERE organization_id = ?",
					Integer.class, orgId)).isEqualTo(15);
		} finally {
			hilos.shutdownNow();
		}
	}

	@Test
	void eliminar_borra_el_reemplazo_pendiente_y_activar_con_hash_repetido_es_409() {
		LibroView original = service.subir(actor, orgId, DATOS, pdf("original"), true);
		jdbc.update("UPDATE libro SET estado = 'LISTO' WHERE id = ?", original.id());
		LibroView reemplazo = service.reemplazar(actor, orgId, original.id(), pdf("nuevo"), true);
		assertThat(reemplazo.reemplazaLibroId()).isEqualTo(original.id());
		// un segundo reemplazo mientras hay uno pendiente
		assertThatThrownBy(() -> service.reemplazar(actor, orgId, original.id(), pdf("otro"), true))
				.isInstanceOf(com.akine.knowledge.domain.exception.LibroEnProcesoException.class);

		// desactivar y volver a subir el mismo contenido: activar el viejo choca con el nuevo
		LibroView uno = service.subir(actor, orgId, DATOS, pdf("repetido"), true);
		service.desactivar(actor, orgId, uno.id());
		LibroView dos = service.subir(actor, orgId, DATOS, pdf("repetido"), true);
		assertThatThrownBy(() -> service.activar(actor, orgId, uno.id()))
				.isInstanceOfSatisfying(LibroDuplicadoException.class,
						e -> assertThat(e.getLibroId()).isEqualTo(dos.id()));

		long antes = version.actual(orgId);
		service.eliminar(actor, orgId, original.id());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM libro WHERE id IN (?, ?)", Integer.class,
				original.id(), reemplazo.id())).isZero();
		assertThat(version.actual(orgId)).isGreaterThan(antes);
	}
}
