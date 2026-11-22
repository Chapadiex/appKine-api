-- Catálogo global de servicios, segunda parte: actividades grupales y de bienestar (yoga, pilates,
-- gimnasia, etc.) y tratamientos individuales que no son prácticas de obra social (RPG, drenaje,
-- masaje). Complementa a V35, que cargó un servicio por cada práctica.
--
-- No llevan código de práctica: no se facturan a una obra social por defecto. Cada centro les pone
-- precio en su actividad y, si alguna obra social se la reconoce, le asocia la práctica a mano.
--
-- Naturaleza: BIENESTAR para lo recreativo, PREVENTIVO para lo que busca evitar problemas
-- (adultos mayores, escuela de espalda) y TERAPEUTICO para lo que trata una condición. Las clases
-- son GRUPALES; no generan registro clínico, salvo las terapéuticas.
--
-- Idempotente: si un servicio global con ese código o nombre ya existe, no se toca.

INSERT INTO servicio (organization_id, codigo, nombre, descripcion, naturaleza, modalidad_default,
                      requiere_caso_clinico_default, genera_registro_clinico_default, active,
                      version, created_at, updated_at)
VALUES
    -- Grupales de bienestar
    (NULL, 'ACT_YOGA', 'Yoga', 'Clase grupal de yoga.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_PILATES_MAT', 'Pilates mat', 'Clase grupal de pilates en colchoneta.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_PILATES_REFORMER', 'Pilates reformer', 'Clase de pilates con máquina reformer.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_STRETCHING', 'Stretching', 'Clase grupal de elongación.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_FUNCIONAL', 'Entrenamiento funcional', 'Clase grupal de entrenamiento funcional.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_TAICHI', 'Tai chi', 'Clase grupal de tai chi.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_MEDITACION', 'Meditación y mindfulness', 'Clase grupal de meditación.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_HIDROGIMNASIA', 'Hidrogimnasia', 'Clase grupal de gimnasia en el agua.', 'BIENESTAR', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- Grupales preventivas
    (NULL, 'ACT_ADULTOS_MAYORES', 'Gimnasia para adultos mayores', 'Clase grupal adaptada para adultos mayores.', 'PREVENTIVO', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_ESCUELA_ESPALDA', 'Escuela de espalda', 'Clase grupal de prevención del dolor de espalda.', 'PREVENTIVO', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- Grupales terapéuticas
    (NULL, 'ACT_PILATES_TERAPEUTICO', 'Pilates terapéutico', 'Pilates orientado a la rehabilitación, en grupos reducidos.', 'TERAPEUTICO', 'GRUPAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'ACT_GIMNASIA_TERAPEUTICA', 'Gimnasia terapéutica', 'Gimnasia orientada a la rehabilitación, en grupos reducidos.', 'TERAPEUTICO', 'GRUPAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- Individuales sin práctica de obra social
    (NULL, 'TRA_RPG', 'Reeducación postural global (RPG)', 'Sesión individual de RPG.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'TRA_DRENAJE', 'Drenaje linfático manual', 'Sesión individual de drenaje linfático.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'TRA_MASAJE', 'Masaje terapéutico', 'Sesión individual de masaje terapéutico.', 'BIENESTAR', 'INDIVIDUAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
