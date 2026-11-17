UPDATE auth_sessions SET replaced_by_id=NULL;
DELETE FROM auth_sessions;
DELETE FROM coin_ledger;
DELETE FROM users;
