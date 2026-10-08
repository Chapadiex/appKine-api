package com.akine.person.domain;

/**
 * Codigos del catalogo de la matriz de permisos que este modulo evalua.
 *
 * <p>{@code paciente:manage} ya estaba declarado en la matriz seccion 5
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
 * <h2>{@code paciente:read}: las LECTURAS del padron (AKINE-DU-6, DP-22)</h2>
 *
 * <p>Hasta DU-6 las lecturas se autorizaban por <b>pertenencia al tenant</b>, y con eso una
 * membership con rol {@code PACIENTE} leia el padron entero de su organizacion. La decision del
 * usuario del 08/10/2026 (DP-22) fue crear {@code paciente:read} <b>solo para el personal</b>
 * —ORG_ADMIN, CONSULTORIO_ADMIN, PROFESIONAL y ADMINISTRATIVO— y dejar al rol {@code PACIENTE}
 * sin ningun acceso a datos de personas. Lo que 03.01 escribio —"aprobar un {@code paciente:read}
 * no lo resolveria"— era cierto para el autoservicio y no para el hueco: el paciente no ve lo
 * suyo, pero deja de ver lo ajeno, que era lo grave. El alcance {@code OWN} (el paciente sobre sus
 * propios datos, que necesita el vinculo cuenta↔persona) queda para despues del MVP: cuando
 * llegue, se suma como alcance de este mismo codigo y no como un permiso nuevo.
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

	/**
	 * Ver el padron y los datos administrativos de una persona (DP-22, AKINE-DU-6).
	 *
	 * <p>Gobierna TODAS las LECTURAS de este modulo: busqueda, ficha, Paciente 360, coberturas,
	 * cobertura aplicable, ordenes, autorizaciones con su saldo, ledger y alertas, elegibilidad y
	 * adjuntos administrativos. Se evalua con la sede del contexto, por el mismo motivo que
	 * {@link #PACIENTE_MANAGE}: sin sede, los alcances de consultorio no cubren la consulta.
	 */
	public static final String PACIENTE_READ = "paciente:read";

	private PermissionCodes() {
		// Catalogo de constantes.
	}
}
