SET SESSION group_concat_max_len = 100000000;
SELECT JSON_OBJECT(
 'tables', (SELECT JSON_ARRAYAGG(JSON_OBJECT('t',table_name,'comment',table_comment)) FROM information_schema.tables WHERE table_schema='akine_ref' AND table_type='BASE TABLE'),
 'views', (SELECT JSON_ARRAYAGG(table_name) FROM information_schema.views WHERE table_schema='akine_ref'),
 'columns', (SELECT JSON_ARRAYAGG(JSON_OBJECT('t',table_name,'c',column_name,'pos',ordinal_position,'type',column_type,'dt',data_type,'null',is_nullable,'def',column_default,'extra',extra,'gen',generation_expression,'coll',collation_name,'comment',column_comment,'len',character_maximum_length,'prec',numeric_precision,'scale',numeric_scale)) FROM information_schema.columns WHERE table_schema='akine_ref'),
 'stats', (SELECT JSON_ARRAYAGG(JSON_OBJECT('t',table_name,'i',index_name,'nonuniq',non_unique,'seq',seq_in_index,'c',column_name,'expr',expression,'type',index_type,'sub',sub_part)) FROM information_schema.statistics WHERE table_schema='akine_ref'),
 'cons', (SELECT JSON_ARRAYAGG(JSON_OBJECT('t',table_name,'n',constraint_name,'type',constraint_type)) FROM information_schema.table_constraints WHERE table_schema='akine_ref'),
 'kcu', (SELECT JSON_ARRAYAGG(JSON_OBJECT('t',table_name,'n',constraint_name,'c',column_name,'pos',ordinal_position,'rt',referenced_table_name,'rc',referenced_column_name)) FROM information_schema.key_column_usage WHERE table_schema='akine_ref'),
 'refs', (SELECT JSON_ARRAYAGG(JSON_OBJECT('t',table_name,'n',constraint_name,'upd',update_rule,'del',delete_rule)) FROM information_schema.referential_constraints WHERE constraint_schema='akine_ref'),
 'checks', (SELECT JSON_ARRAYAGG(JSON_OBJECT('t',tc.table_name,'n',cc.constraint_name,'clause',cc.check_clause)) FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name AND tc.constraint_type='CHECK' WHERE cc.constraint_schema='akine_ref'),
 'triggers', (SELECT JSON_ARRAYAGG(JSON_OBJECT('n',trigger_name,'t',event_object_table,'ev',event_manipulation,'timing',action_timing,'body',action_statement)) FROM information_schema.triggers WHERE trigger_schema='akine_ref'),
 'routines', (SELECT JSON_ARRAYAGG(routine_name) FROM information_schema.routines WHERE routine_schema='akine_ref'),
 'events', (SELECT JSON_ARRAYAGG(event_name) FROM information_schema.events WHERE event_schema='akine_ref')
);
