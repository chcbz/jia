"""Exercise shipped migration/mapper SQL in owned UUID schemas, never application DBs."""
import argparse
import pathlib
import re
import subprocess
import unittest
import uuid

MODULE = pathlib.Path(__file__).resolve().parents[3]
RESOURCE = MODULE / 'src/main/resources/db'
SOCKET = None


class MembershipSqlTest(unittest.TestCase):
    def sql(self, text, *, database=True, success=True):
        args = ['mysql', '--no-defaults', '--protocol=SOCKET', '--socket=' + SOCKET,
                '--user=root', '--batch', '--skip-column-names', '--default-character-set=utf8mb4']
        if database:
            args.append('--database=' + self.database)
        result = subprocess.run(args, input=text, text=True, capture_output=True, timeout=30)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result.stdout.strip()

    def setUp(self):
        self.database = 'cyf_membership_test_' + uuid.uuid4().hex
        self.sql('CREATE DATABASE `' + self.database + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;', database=False)
        self.addCleanup(lambda: self.sql('DROP DATABASE `' + self.database + '`;', database=False))
        self.sql((RESOURCE / 'user-business-membership.sql').read_text())
        self.sql('CREATE TABLE user_info(id bigint PRIMARY KEY, tenant_id varchar(100),'
                 'client_id varchar(100),create_time bigint, nickname varchar(50));'
                 'CREATE TABLE user_role_rel(user_id bigint,role_id bigint);')
        self.sql((RESOURCE / 'user-business-membership-import.sql').read_text())

    def seed(self):
        self.sql("INSERT INTO user_info VALUES(1,'0','web',100,'alice'),"
                 "(2,'0',NULL,NULL,'bob'),(3,'1','gray',200,'carol');")

    def test_exact_import_preserves_source_and_grants_no_roles(self):
        self.seed()
        before = self.sql('SELECT * FROM user_info ORDER BY id;')
        self.assertEqual(self.sql('CALL cyf_import_user_business_membership();'), '3')
        self.assertEqual(self.sql('SELECT * FROM user_info ORDER BY id;'), before)
        self.assertEqual(self.sql("SELECT tenant_id,client_id,user_id FROM user_business_membership ORDER BY user_id;"),
                         '0\tweb\t1\n0\t\t2\n1\tgray\t3')
        self.assertEqual(self.sql('SELECT COUNT(*) FROM user_role_rel;'), '0')
        self.assertEqual(self.sql('SELECT create_time>0 FROM user_business_membership WHERE user_id=2;'), '1')

    def test_existing_target_never_overwritten(self):
        self.seed()
        self.sql('CALL cyf_import_user_business_membership();')
        before = self.sql('SELECT * FROM user_business_membership ORDER BY user_id;')
        self.sql('CALL cyf_import_user_business_membership();', success=False)
        self.assertEqual(self.sql('SELECT * FROM user_business_membership ORDER BY user_id;'), before)

    def test_invalid_affiliation_rejects_whole_import_and_releases_lock(self):
        self.seed()
        for field, value in [('tenant_id', 'NULL'), ('tenant_id', "''"), ('tenant_id', "'0 '"),
                             ('client_id', "''"), ('client_id', "' web'"),
                             ('client_id', 'REPEAT(\'x\',51)'), ('client_id', 'CONCAT(\'web\',CHAR(10))')]:
            with self.subTest(field=field, value=value):
                self.sql('UPDATE user_info SET ' + field + '=' + value + ' WHERE id=3;')
                self.sql('CALL cyf_import_user_business_membership();', success=False)
                self.assertEqual(self.sql('SELECT COUNT(*) FROM user_business_membership;'), '0')
                self.sql("UPDATE user_info SET tenant_id='1',client_id='gray' WHERE id=3;")
        self.assertEqual(self.sql('CALL cyf_import_user_business_membership();'), '3')

    def test_filters_work_after_drop_without_cross_environment_union(self):
        self.seed()
        self.sql('CALL cyf_import_user_business_membership();'
                 'ALTER TABLE user_info DROP COLUMN tenant_id,DROP COLUMN client_id;')
        def members(tenant, client=None):
            condition = "bm.tenant_id='" + tenant + "' AND OCTET_LENGTH(bm.tenant_id)=OCTET_LENGTH('" + tenant + "')"
            if client is not None:
                condition += " AND bm.client_id<>'' AND bm.client_id='" + client + "' AND OCTET_LENGTH(bm.client_id)=OCTET_LENGTH('" + client + "')"
            return self.sql('SELECT u.id FROM user_info u WHERE EXISTS(SELECT 1 FROM user_business_membership bm '
                            'WHERE bm.user_id=u.id AND ' + condition + ') ORDER BY u.id;')
        self.assertEqual(members('0'), '1\n2')
        self.assertEqual(members('1'), '3')
        self.assertEqual(members('0', 'web'), '1')
        for tenant, client in [('0', 'gray'), ('1', 'web'), ('0', ''), ('0', 'Web'), ('0', 'web '), ('0 ', 'web')]:
            self.assertEqual(members(tenant, client), '')

    def test_actual_mapper_is_idempotent_and_rolls_back_with_identity(self):
        source = (MODULE / 'src/main/java/cn/jia/user/mapper/UserBusinessMembershipMapper.java').read_text()
        expression = re.search(r'@Insert\((.*?)\)\s+int ensure', source, re.S).group(1)
        statement = ''.join(re.findall(r'"([^"\\]*)"', expression))
        for key, value in {'tenantId': "'0'", 'clientId': "'web'", 'userId': '1', 'now': '123'}.items():
            statement = statement.replace('#{' + key + '}', value)
        self.assertNotIn('#{', statement)
        self.sql("START TRANSACTION;INSERT INTO user_info VALUES(1,'0','web',1,'alice');" + statement + ';ROLLBACK;')
        self.assertEqual(self.sql('SELECT COUNT(*) FROM user_info;SELECT COUNT(*) FROM user_business_membership;'), '0\n0')
        self.sql(statement + ';' + statement.replace('123', '456') + ';')
        self.assertEqual(self.sql('SELECT COUNT(*),MIN(create_time) FROM user_business_membership;'), '1\t123')

    def test_actual_delete_mapper_removes_only_deleted_identity_memberships(self):
        self.seed()
        self.sql('CALL cyf_import_user_business_membership();')
        self.sql("INSERT INTO user_business_membership VALUES('1','gray',1,123);")
        source = (MODULE / 'src/main/java/cn/jia/user/mapper/UserBusinessMembershipMapper.java').read_text()
        statement = re.search(r'@org.apache.ibatis.annotations.Delete\("([^"\n]+)"\)', source).group(1)
        statement = statement.replace('#{userId}', '1')
        before = self.sql('SELECT * FROM user_business_membership ORDER BY user_id,tenant_id;')
        self.sql('START TRANSACTION;DELETE FROM user_info WHERE id=1;' + statement + ';ROLLBACK;')
        self.assertEqual(self.sql('SELECT * FROM user_business_membership ORDER BY user_id,tenant_id;'), before)
        self.assertEqual(self.sql('SELECT COUNT(*) FROM user_info;'), '3')
        self.sql('START TRANSACTION;DELETE FROM user_info WHERE id=1;' + statement + ';COMMIT;')
        self.assertEqual(self.sql('SELECT user_id FROM user_business_membership ORDER BY user_id;'), '2\n3')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--socket', required=True)
    args, remaining = parser.parse_known_args()
    SOCKET = args.socket
    unittest.main(argv=[__file__] + remaining)
