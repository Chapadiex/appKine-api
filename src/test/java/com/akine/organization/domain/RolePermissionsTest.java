package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La asignacion base por rol, comparada fila por fila contra la tabla §6 de
 * {@code docs/seguridad/matriz-permisos-minima.md}.
 *
 * <h2>Por que la tabla esta escrita aca otra vez</h2>
 *
 * <p>La duplicacion es el punto. {@link RolePermissions} es la matriz hecha codigo; esta tabla
 * es la matriz hecha fixture. Si alguien toca el mapa sin tocar la matriz —o al reves— el build
 * falla, que es la unica forma de que un documento vinculante siga siendo vinculante seis meses
 * despues. Un test que leyera el mapa para compararlo consigo mismo no probaria nada.
 *
 * <h2>Que se prueba, ademas de las celdas que conceden</h2>
 *
 * <p>Las celdas que <b>deniegan</b> se prueban con el mismo cuidado. Que
 * {@code sesion:register} no habilite nada en Fase 1 documenta que falta por diseño y no por
 * descuido; el dia que alguien lo agregue sin su modulo, este test se lo va a decir.
 */
class RolePermissionsTest {

	// =================================================================================
	// La tabla §6, celda por celda
	// =================================================================================

	@ParameterizedTest(name = "{0} tiene {1} con alcance {2}")
	@CsvSource({
			// PLATFORM_ADMIN — "Global" en toda la columna salvo la auditoria clinica, que la
			// matriz marca "Restringido (grant + soporte)": el rol mas alto del sistema tiene el
			// acceso MAS restringido a datos clinicos de un tenant, no el mas amplio (§1.3).
			"PLATFORM_ADMIN,     TENANT_MANAGE,           GLOBAL",
			// SOPORTE y no GLOBAL: la matriz §7 exige que el acceso de un PLATFORM_ADMIN a los
			// datos de un tenant sea "solo por acceso de soporte justificado y auditado", y con
			// GLOBAL el soporte era opcional. La contradiccion con §6 esta explicada en
			// RolePermissions.
			"PLATFORM_ADMIN,     TENANT_READ,             SOPORTE",
			"PLATFORM_ADMIN,     CONSULTORIO_MANAGE,      GLOBAL",
			"PLATFORM_ADMIN,     COLABORADOR_MANAGE,      GLOBAL",
			"PLATFORM_ADMIN,     COLABORADOR_READ,        SOPORTE",
			// Idem: leer el rastro de un tenant sin dejar rastro de haberlo leido era el peor
			// de los agujeros, porque la auditoria es el mapa de lo que hace un cliente.
			"PLATFORM_ADMIN,     AUDITORIA_READ,          SOPORTE",
			"PLATFORM_ADMIN,     AUDITORIA_READ_CLINICA,  RESTRINGIDO",

			// ORG_ADMIN — todo con alcance de organizacion.
			"ORG_ADMIN,          TENANT_READ,             ORGANIZACION",
			"ORG_ADMIN,          CONSULTORIO_MANAGE,      ORGANIZACION",
			"ORG_ADMIN,          COLABORADOR_MANAGE,      ORGANIZACION",
			"ORG_ADMIN,          COLABORADOR_READ,        ORGANIZACION",
			"ORG_ADMIN,          AUDITORIA_READ,          ORGANIZACION",

			// CONSULTORIO_ADMIN — todo acotado a SU sede.
			"CONSULTORIO_ADMIN,  CONSULTORIO_MANAGE,      CONSULTORIO",
			"CONSULTORIO_ADMIN,  COLABORADOR_MANAGE,      CONSULTORIO",
			"CONSULTORIO_ADMIN,  COLABORADOR_READ,        CONSULTORIO",
			"CONSULTORIO_ADMIN,  AUDITORIA_READ,          CONSULTORIO",

			// PROFESIONAL y ADMINISTRATIVO — en F1 solo la lista de colaboradores de su sede.
			"PROFESIONAL,        COLABORADOR_READ,        CONSULTORIO",
			"ADMINISTRATIVO,     COLABORADOR_READ,        CONSULTORIO"})
	@DisplayName("Cada celda que concede lo hace con el alcance que dice la matriz")
	void las_celdas_que_conceden(RoleCode rol, PermissionCode permiso, PermissionScope alcance) {
		assertThat(RolePermissions.baseScope(rol, permiso)).contains(alcance);
	}

	@ParameterizedTest(name = "{0} NO tiene {1}")
	@CsvSource({
			// ORG_ADMIN no gestiona el tenant: la matriz §4 acota su "Limitado" a editar su
			// organizacion y ver su suscripcion, y deja el cambio de plan y la suspension para
			// PLATFORM_ADMIN.
			"ORG_ADMIN,          TENANT_MANAGE",
			// "No por defecto (grant)": no es base para nadie. Ponerlo aca con algun alcance
			// convertiria en implicito justo lo que la matriz define como explicito.
			"ORG_ADMIN,          AUDITORIA_READ_CLINICA",
			"CONSULTORIO_ADMIN,  AUDITORIA_READ_CLINICA",
			// CONSULTORIO_ADMIN no lee los datos del tenant.
			"CONSULTORIO_ADMIN,  TENANT_READ",
			"CONSULTORIO_ADMIN,  TENANT_MANAGE",
			// PROFESIONAL y ADMINISTRATIVO no administran nada en F1.
			"PROFESIONAL,        COLABORADOR_MANAGE",
			"PROFESIONAL,        AUDITORIA_READ",
			"PROFESIONAL,        TENANT_READ",
			"ADMINISTRATIVO,     COLABORADOR_MANAGE",
			"ADMINISTRATIVO,     AUDITORIA_READ",
			"ADMINISTRATIVO,     CONSULTORIO_MANAGE"})
	@DisplayName("Cada celda que deniega devuelve vacio, y vacio significa denegado")
	void las_celdas_que_deniegan(RoleCode rol, PermissionCode permiso) {
		assertThat(RolePermissions.baseScope(rol, permiso)).isEmpty();
	}

	@Test
	@DisplayName("PACIENTE no tiene ningun permiso en Fase 1")
	void paciente_no_tiene_nada_en_f1() {
		// Sus celdas de la matriz —"Propio", "Propia autorizada"— viven en acciones de F3 y F4.
		assertThat(RolePermissions.baseOf(RoleCode.PACIENTE)).isEmpty();
	}

	// =================================================================================
	// Los permisos de fases futuras: probar que DENIEGAN es la mitad importante
	// =================================================================================

	@ParameterizedTest
	@EnumSource(value = PermissionCode.class, names = {
			"PACIENTE_MANAGE", "HC_READ", "HC_WRITE", "CASO_CREATE",
			"SESION_REGISTER", "CONVENIO_MANAGE", "COBRO_REGISTER", "CAJA_OPERATE", "REPORTE_READ"})
	@DisplayName("Los permisos de F3 en adelante estan declarados y no los tiene NINGUN rol")
	void los_permisos_de_fases_futuras_deniegan_para_todos(PermissionCode permiso) {
		// Estan en el catalogo para que agregar una fase sea sumar filas y no rehacer el modelo.
		// Que ninguno habilite nada todavia documenta que faltan por diseño: el dia que alguien
		// los conecte sin su modulo, este test se lo dice.
		for (RoleCode rol : RoleCode.values()) {
			assertThat(RolePermissions.baseScope(rol, permiso))
					.as("%s no puede tener %s antes de que exista su modulo", rol, permiso)
					.isEmpty();
		}
	}

	// =================================================================================
	// Grants
	// =================================================================================

	@Test
	@DisplayName("El unico permiso otorgable como grant en F1 es auditoria:read-clinica")
	void el_unico_grant_de_f1() {
		// Matriz §6: es el unico "No por defecto (grant)" de la fase. Cualquier otro codigo se
		// rechaza con 400 en vez de escribir una fila que el evaluador nunca va a mirar.
		assertThat(RolePermissions.otorgablesComoGrant())
				.containsExactly(PermissionCode.AUDITORIA_READ_CLINICA);
	}

	// =================================================================================
	// Contrato de la clase
	// =================================================================================

	@Test
	@DisplayName("Un rol o un permiso nulos devuelven vacio en vez de explotar")
	void los_nulos_deniegan() {
		// Fail-closed: un evaluador que explota ante un dato faltante convierte un error de
		// programacion en un 500; uno que ante la duda concede es un agujero.
		assertThat(RolePermissions.baseScope(null, PermissionCode.TENANT_READ)).isEmpty();
		assertThat(RolePermissions.baseScope(RoleCode.ORG_ADMIN, null)).isEmpty();
		assertThat(RolePermissions.baseOf(null)).isEmpty();
	}

	@Test
	@DisplayName("La tabla es inmutable: nadie puede otorgarse un permiso en runtime")
	void la_tabla_es_inmutable() {
		var permisos = RolePermissions.baseOf(RoleCode.PROFESIONAL);
		assertThat(permisos).isNotEmpty();

		Optional<PermissionScope> antes =
				RolePermissions.baseScope(RoleCode.PROFESIONAL, PermissionCode.TENANT_MANAGE);
		assertThat(antes).isEmpty();

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> permisos.put(
						PermissionCode.TENANT_MANAGE, PermissionScope.GLOBAL))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("El codigo textual es el contrato hacia afuera, y no cambia de forma")
	void los_codigos_textuales_son_estables() {
		// El frontend y el spi trabajan con estos textos. Renombrarlos es un cambio de contrato,
		// no un refactor.
		assertThat(PermissionCode.COLABORADOR_MANAGE.code()).isEqualTo("colaborador:manage");
		assertThat(PermissionCode.AUDITORIA_READ_CLINICA.code()).isEqualTo("auditoria:read-clinica");
		assertThat(PermissionCode.desde("tenant:read")).contains(PermissionCode.TENANT_READ);
	}

	@Test
	@DisplayName("Un codigo desconocido devuelve vacio: es un 400 del cliente, no un 500")
	void un_codigo_desconocido_no_explota() {
		assertThat(PermissionCode.desde("no:existe")).isEmpty();
		assertThat(PermissionCode.desde("")).isEmpty();
		assertThat(PermissionCode.desde(null)).isEmpty();
	}

	@Test
	@DisplayName("Solo GLOBAL y ORGANIZACION cubren la organizacion entera")
	void que_alcances_cubren_la_organizacion() {
		assertThat(PermissionScope.GLOBAL.cubreLaOrganizacion()).isTrue();
		assertThat(PermissionScope.ORGANIZACION.cubreLaOrganizacion()).isTrue();
		assertThat(PermissionScope.CONSULTORIO.cubreLaOrganizacion()).isFalse();
		assertThat(PermissionScope.OWN.cubreLaOrganizacion()).isFalse();
		assertThat(PermissionScope.CATALOGO.cubreLaOrganizacion()).isFalse();
		assertThat(PermissionScope.SOPORTE.cubreLaOrganizacion()).isFalse();
		assertThat(PermissionScope.RESTRINGIDO.cubreLaOrganizacion()).isFalse();
	}
}
