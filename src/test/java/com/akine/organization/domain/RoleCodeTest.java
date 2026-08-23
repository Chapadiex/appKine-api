package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fija la lista cerrada de roles de la matriz de permisos aprobada
 * ({@code docs/seguridad/matriz-permisos-minima.md}, vinculante; RN-M05-006).
 *
 * <p>Este test existe porque la decision ya se violo una vez: agregar un rol por
 * denominacion —tipicamente {@code OWNER} para distinguir al fundador, o un {@code ADMIN}
 * generico— rompe la matriz de permisos en silencio. El rol de seguridad y la condicion de
 * fundador son dimensiones distintas (RN-M05-005): lo segundo es
 * {@link Membership#isFounder()}, un atributo, no un rol.
 *
 * <p>En produccion un rol de mas significa una fila de {@code membership} con un
 * {@code role_code} que la matriz no contempla, y por lo tanto un permiso que nadie definio.
 */
class RoleCodeTest {

	@Test
	@DisplayName("Los roles son exactamente los seis de la matriz aprobada")
	void los_roles_son_exactamente_los_seis_aprobados() {
		// containsExactly y no containsAll: agregar un rol tiene que romper este test.
		// El orden tambien se fija porque es el de la matriz, de plataforma a paciente.
		assertThat(RoleCode.values()).containsExactly(
				RoleCode.PLATFORM_ADMIN,
				RoleCode.ORG_ADMIN,
				RoleCode.CONSULTORIO_ADMIN,
				RoleCode.PROFESIONAL,
				RoleCode.ADMINISTRATIVO,
				RoleCode.PACIENTE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"OWNER", "ADMIN", "SUPERADMIN", "USER", "KINESIOLOGO"})
	@DisplayName("No existen roles fuera de la matriz")
	void no_existen_roles_fuera_de_la_matriz(String nombreProhibido) {
		// OWNER y ADMIN son los dos que se cuelan siempre: el propietario que fundo la
		// organizacion se representa con ORG_ADMIN + is_founder = true, no con un rol propio.
		assertThat(Arrays.stream(RoleCode.values()).map(Enum::name))
				.doesNotContain(nombreProhibido);

		assertThatThrownBy(() -> RoleCode.valueOf(nombreProhibido))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Los nombres se persisten literales: renombrar uno rompe las filas existentes")
	void los_nombres_son_estables() {
		// La columna role_code guarda el name() como texto (EnumType.STRING). Renombrar un
		// valor deja las filas viejas apuntando a un enum que ya no existe y la lectura
		// explota al hidratar la membership.
		assertThat(RoleCode.ORG_ADMIN.name()).isEqualTo("ORG_ADMIN");
		assertThat(RoleCode.valueOf("PLATFORM_ADMIN")).isEqualTo(RoleCode.PLATFORM_ADMIN);
		assertThat(RoleCode.valueOf("CONSULTORIO_ADMIN")).isEqualTo(RoleCode.CONSULTORIO_ADMIN);
		assertThat(RoleCode.valueOf("PROFESIONAL")).isEqualTo(RoleCode.PROFESIONAL);
		assertThat(RoleCode.valueOf("ADMINISTRATIVO")).isEqualTo(RoleCode.ADMINISTRATIVO);
		assertThat(RoleCode.valueOf("PACIENTE")).isEqualTo(RoleCode.PACIENTE);
	}

	@Test
	@DisplayName("Los nombres entran en la columna de 48 caracteres")
	void los_nombres_entran_en_la_columna() {
		// role_code es VARCHAR(48). Un nombre mas largo se trunca o falla al insertar, y el
		// error aparece recien en produccion con el rol nuevo.
		assertThat(RoleCode.values())
				.allSatisfy(rol -> assertThat(rol.name().length()).isLessThanOrEqualTo(48));
	}
}
