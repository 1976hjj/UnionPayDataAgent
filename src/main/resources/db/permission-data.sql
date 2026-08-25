INSERT OR IGNORE INTO bi_agent_permission_dimension (dimension_id, required, enabled, sort_order)
VALUES ('acq_reg_ch', 1, 1, 10);

INSERT OR IGNORE INTO bi_agent_permission_dimension (dimension_id, required, enabled, sort_order)
VALUES ('iss_dq_ch', 1, 1, 20);

INSERT OR IGNORE INTO bi_agent_user_data_scope (login_username, dimension_id, dimension_value)
VALUES ('demo-user', 'acq_reg_ch', '中国大陆');

INSERT OR IGNORE INTO bi_agent_user_data_scope (login_username, dimension_id, dimension_value)
VALUES ('demo-user', 'iss_dq_ch', '中国大陆');

INSERT OR IGNORE INTO bi_agent_user_data_scope (login_username, dimension_id, dimension_value)
VALUES ('zhangsan', 'acq_reg_ch', '中国大陆');

INSERT OR IGNORE INTO bi_agent_user_data_scope (login_username, dimension_id, dimension_value)
VALUES ('zhangsan', 'iss_dq_ch', '中国大陆');

INSERT OR IGNORE INTO bi_agent_user_data_scope (login_username, dimension_id, dimension_value)
VALUES ('lisi', 'acq_reg_ch', '中国大陆');

INSERT OR IGNORE INTO bi_agent_user_data_scope (login_username, dimension_id, dimension_value)
VALUES ('lisi', 'iss_dq_ch', '中国大陆');
