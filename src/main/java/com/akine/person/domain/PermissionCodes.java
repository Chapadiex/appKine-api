package com.akine.person.domain;

/**
 * Codigos del catalogo de la matriz de permisos que este modulo evalua.
 *
 * <p><b>Ninguno es nuevo.</b> {@code paciente:manage} ya estaba declarado en la matriz seccion 5
 * con fase destino F3, y en {@code organization.domain.PermissionCode} con la nota "sin
 * asignacion base todavia: deniega". Esta etapa no lo inventa: le da su asignacion base por rol,
 * que es lo que la matriz seccion 4 ya decia y nadie habia cableado. La enmienda esta escrita en
 * {@code docs/seguridad/matriz-permisos-minima.md}.
 *
 * <h2>Por que es literal y no el enum de {@code organization}</h2>
 *
 * <p>{@code organization.domain.PermissionCode} es privado de ese modulo y ArchUnit prohibe
 * importarlo ({@code modulos_solo_se_alcanzan_por_su_spi}). El {@code spi} lo expone a proposito
 * como {@code String}. Mismo patron que {@code resource.domain.PermissionCodes} y
 * {@code offering.domain.PermissionCodes}.
 *
 * <h2>Lo que NO esta aca: no existe {@code paciente:read}</h2>
 *
 * <p>La matriz no lo tiene, y esta etapa no lo crea: la matriz no la amplia una etapa, mismo
 * criterio que 02.04, 02.05 y 02.06. Las LECTURAS del padron se autorizan por <b>pertenencia al
 * tenant</b> —tener contexto de trabajo activo en esa organizacion—, que es el mismo mecanismo
 * interino con el que 02.05 autoriza las lecturas del catalogo clinico.
 *
 * <p><b>Y hay que decir que eso es mas amplio de lo que la matriz seccion 4 pretende:</b> con
 * pertenencia sola, una membership con rol {@code PACIENTE} lee el padron entero de su
 * organizacion. La matriz le asigna "Propio" a esa celda, o sea alcance {@code OWN}, y ese
 * alcance <b>no esta implementado en ninguna parte del sistema</b> — no hay vinculo entre una
 * cuenta y una persona, justamente porque RN-M07-002 los separa y el vinculo es de la etapa de
 * autoservicio. Aprobar un {@code paciente:read} tampoco lo resolveria: el problema no es el
 * codigo de permiso sino el alcance propio. Queda escrito como hueco conocido, con la misma
 * franqueza con la que 02.05 declaro el suyo, y no se tapa con un permiso que no cambia nada.
 */
public final class PermissionCodes {

	/**
	 * Gestionar paciente. Matriz seccion 5, fase F3.
	 *
	 * <p>Gobierna las MUTACIONES del padron: alta de persona, edicion y activacion de perfil de
	 * paciente.
	 *
	 * <h2>Se evalua CON la sede del contexto, aunque la persona sea de la organizacion</h2>
	 *
	 * <p>Es la parte contraintuitiva y conviene leerla antes de "simplificarla". Una
	 * {@link Persona} no tiene {@code consultorio_id}: pertenece a la organizacion entera. El
	 * reflejo es entonces evaluar el permiso con {@code consultorioId = null}, o sea "sobre la
	 * organizacion". <b>Eso rompe la matriz.</b> {@code PermissionEvaluatorService.alcanceCubre}
	 * concede un alcance de sede unicamente cuando la consulta nombra una sede: sin ella devuelve
	 * {@code false}, porque una decision sobre la organizacion entera solo la cubre un alcance de
	 * organizacion. Con la consulta sin sede pasarian {@code ORG_ADMIN} y el rol de plataforma, y
	 * quedarian afuera {@code CONSULTORIO_ADMIN} y {@code ADMINISTRATIVO}, a los que la matriz
	 * seccion 4 les da "Si" en la fila "Gestionar paciente" — es decir, el recepcionista no
	 * podria dar de alta a nadie, que es literalmente su trabajo.
	 *
	 * <p>Por eso la consulta lleva <b>la sede del contexto de trabajo activo</b>, igual que las
	 * mutaciones de espacios, horarios y ofertas. Un {@code ORG_ADMIN} pasa igual —su alcance
	 * cubre la organizacion y por lo tanto cualquier sede suya— y un {@code CONSULTORIO_ADMIN} o
	 * un {@code ADMINISTRATIVO} pasan sobre la sede en la que estan parados.
	 *
	 * <p>Consecuencia que hay que conocer: <b>sin contexto de sede elegido no se muta el
	 * padron</b>, y se responde 403. Es el mismo comportamiento que ya tienen las ofertas y la
	 * disponibilidad. Lo que NO significa es que la persona quede atada a esa sede: la fila se
	 * escribe con {@code organization_id} y nada mas, y despues se ve y se edita desde cualquier
	 * sede de la organizacion.
	 */
	public static final String PACIENTE_MANAGE = "paciente:manage";

	private PermissionCodes() {
		// Catalogo de constantes.
	}
}
