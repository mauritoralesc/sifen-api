INSERT INTO user_company_memberships (user_id, company_id, role)
SELECT id, company_id, role
FROM users;
