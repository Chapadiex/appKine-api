package com.akine.knowledge.application;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;

import com.akine.knowledge.domain.exception.LibroDuplicadoException;
import com.akine.knowledge.domain.exception.LibroEnProcesoException;
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

import java.util.ArrayList;
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
				.isInstanceOf(LibroEnProcesoException.class);

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

	@Test
	void dos_reemplazos_simultaneos_del_mismo_libro_dan_uno_202_y_un_libro_en_proceso() throws Exception {
		LibroView original = service.subir(actor, orgId, DATOS, pdf("base"), true);
		jdbc.update("UPDATE libro SET estado = 'LISTO' WHERE id = ?", original.id());
		ExecutorService hilos = Executors.newFixedThreadPool(2);
		try {
			CyclicBarrier salida = new CyclicBarrier(2);
			List<Future<Object>> futuros = new ArrayList<>();
			for (String cuerpo : List.of("nuevo-a", "nuevo-b")) {
				futuros.add(hilos.submit(() -> {
					salida.await();
					try {
						return service.reemplazar(actor, orgId, original.id(), pdf(cuerpo), true);
					} catch (Exception e) {
						return e;
					}
				}));
			}
			List<Object> resultados = List.of(futuros.get(0).get(60, TimeUnit.SECONDS),
					futuros.get(1).get(60, TimeUnit.SECONDS));
			assertThat(resultados.stream().filter(LibroView.class::isInstance)).hasSize(1);
			assertThat(resultados.stream().filter(LibroEnProcesoException.class::isInstance))
					.as("%s", resultados).hasSize(1);
		} finally {
			hilos.shutdownNow();
		}
	}

	@Test
	void activar_un_libro_cuyo_hash_es_el_de_un_reemplazo_pendiente_es_409() {
		LibroView a = service.subir(actor, orgId, DATOS, pdf("A"), true);
		service.desactivar(actor, orgId, a.id());
		LibroView x = service.subir(actor, orgId, DATOS, pdf("X"), true);
		jdbc.update("UPDATE libro SET estado = 'LISTO' WHERE id = ?", x.id());
		LibroView reemplazo = service.reemplazar(actor, orgId, x.id(), pdf("A"), true);

		assertThatThrownBy(() -> service.activar(actor, orgId, a.id()))
				.isInstanceOfSatisfying(LibroDuplicadoException.class,
						e -> assertThat(e.getLibroId()).isEqualTo(reemplazo.id()));
	}

	@Test
	void un_reemplazo_en_error_no_bloquea_y_se_elimina_con_auditoria_de_titulo_y_hash() {
		LibroView original = service.subir(actor, orgId, DATOS, pdf("orig"), true);
		jdbc.update("UPDATE libro SET estado = 'LISTO' WHERE id = ?", original.id());
		LibroView fallido = service.reemplazar(actor, orgId, original.id(), pdf("r1"), true);
		jdbc.update("UPDATE libro SET estado = 'ERROR' WHERE id = ?", fallido.id());

		// el original se puede reprocesar y el reemplazo en ERROR queda como esta
		long antes = version.actual(orgId);
		assertThat(service.reprocesar(actor, orgId, original.id()).estado().name()).isEqualTo("PENDIENTE");
		assertThat(version.actual(orgId)).isGreaterThan(antes);
		jdbc.update("UPDATE libro SET estado = 'LISTO' WHERE id = ?", original.id());

		LibroView nuevo = service.reemplazar(actor, orgId, original.id(), pdf("r2"), true);
		assertThat(nuevo.reemplazaLibroId()).isEqualTo(original.id());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM libro WHERE id = ?", Integer.class,
				fallido.id())).isZero();
		String detalles = jdbc.queryForObject("""
				SELECT CAST(details AS CHAR) FROM audit_event
				 WHERE event_type = 'LIBRO_ELIMINADO' AND entity_id = ?
				""", String.class, fallido.id());
		assertThat(detalles).contains("titulo").contains("hashSha256").contains("reemplazo-fallido");
	}

	@Test
	void un_reemplazo_en_error_no_cuenta_como_hash_en_uso() {
		LibroView original = service.subir(actor, orgId, DATOS, pdf("orig2"), true);
		jdbc.update("UPDATE libro SET estado = 'LISTO' WHERE id = ?", original.id());
		LibroView fallido = service.reemplazar(actor, orgId, original.id(), pdf("X"), true);
		jdbc.update("UPDATE libro SET estado = 'ERROR' WHERE id = ?", fallido.id());

		LibroView alta = service.subir(actor, orgId, DATOS, pdf("X"), true);

		assertThat(alta.reemplazaLibroId()).isNull();
	}
}
