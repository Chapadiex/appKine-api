package com.akine;

/**
 * Perfiles que existen solo para las pruebas.
 *
 * <h2>Por que hace falta {@link #SOLO_SLICE}</h2>
 *
 * <p>Un controller de sonda —esos que los slices montan para poder afirmar si un request
 * <b>llego o no llego</b> al otro lado de un filtro— tiene que llevar {@code @RestController}:
 * desde Spring 7, {@code RequestMappingHandlerMapping} solo trata como handler a las clases
 * anotadas con {@code @Controller}, y un {@code @RequestMapping} a nivel de tipo ya no alcanza.
 *
 * <p>Pero {@code @RestController} es un estereotipo, y {@code src/test} esta en el classpath:
 * el escaneo de componentes de cualquier {@code @SpringBootTest} —incluido el que genera el
 * OpenAPI— levanta cualquier clase bajo {@code com.akine} que lleve uno. Sin este perfil, los
 * controllers de sonda <b>se publican en el contrato</b>. Ya paso: uno que mapeaba las rutas
 * reales de identidad devolviendo un {@code String} piso los schemas de login, refresh y logout,
 * y el cliente TypeScript generado dejo de compilar porque recibia {@code string} donde esperaba
 * {@code AccessTokenResponse}.
 *
 * <p>La regla, entonces: <b>todo controller declarado en fuentes de test lleva
 * {@code @Profile(SOLO_SLICE)}</b>, y el slice que lo usa lo activa con
 * {@code @ActiveProfiles(SOLO_SLICE)}. El contexto completo nunca activa ese perfil, asi que el
 * bean no se registra y el contrato no lo ve.
 */
public final class PerfilesDeTest {

	/**
	 * Perfil bajo el cual viven los controllers de sonda de los slices.
	 *
	 * <p>Deliberadamente no existe ningun {@code application-solo-slice.yml}: el perfil no
	 * aporta configuracion, solo acota donde se registran esos beans.
	 */
	public static final String SOLO_SLICE = "solo-slice";

	private PerfilesDeTest() {
	}
}
