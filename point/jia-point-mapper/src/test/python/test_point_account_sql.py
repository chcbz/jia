"""Lightweight SQL-only check in an owned disposable schema on an explicit socket.

No application build, network credentials, production reads, or production DDL.
Run: python3 test_point_account_sql.py --socket /absolute/mysql.sock
"""
import argparse
import pathlib
import re
import subprocess
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor

HERE = pathlib.Path(__file__).resolve()
MODULE = HERE.parents[3]
RESOURCE = MODULE / 'src/main/resources/db'
MAPPER = MODULE / 'src/main/java/cn/jia/point/mapper/PointAccountMapper.java'
SOCKET = None


def sql_fragments(expression, scope=''):
    """Read the actual Java SQL constant/annotation rather than maintain a copy."""
    tokens = re.findall(r'"((?:[^"\\]|\\.)*)"|\b(SCOPE)\b', expression)
    return ''.join(text if not symbol else scope for text, symbol in tokens)


class PointAccountSqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = MAPPER.read_text()
        scope = sql_fragments(re.search(r'String SCOPE = (.*?);', source, re.S).group(1))
        cls.statements = {}
        for kind, expression, method in re.findall(
                r'@(Select|Insert|Update)\((.*?)\)\s+(?:Integer|int)\s+(\w+)\(', source, re.S):
            cls.statements[method] = sql_fragments(expression, scope)
        if set(cls.statements) != {'balance', 'ensureAccount', 'lockBalance', 'change'}:
            raise AssertionError('Mapper SQL extraction incomplete')

    def command(self, statement, database=True, success=True):
        command = ['mysql', '--no-defaults', '--protocol=SOCKET', '--socket=' + SOCKET,
                   '--user=root', '--batch', '--skip-column-names', '--default-character-set=utf8mb4']
        if database:
            command += ['--database=' + self.database]
        result = subprocess.run(command, input=statement, text=True, capture_output=True, timeout=30)
        if success and result.returncode:
            self.fail(result.stderr)
        if not success:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result.stdout.strip(), result.stderr

    def setUp(self):
        self.database = 'cyf_point_test_' + uuid.uuid4().hex
        self.command('CREATE DATABASE `' + self.database + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;', False)
        self.addCleanup(lambda: self.command('DROP DATABASE `' + self.database + '`;', False))
        self.command((RESOURCE / 'point-account.sql').read_text())
        self.command('''CREATE TABLE user_info (
            id bigint PRIMARY KEY, jiacn varchar(50), point int NULL,
            tenant_id varchar(50) DEFAULT '0', create_time bigint, update_time bigint
        ) ENGINE=InnoDB;''')
        self.command((RESOURCE / 'point-account-import-legacy.sql').read_text())

    def mapper(self, name, tenant='0', user='alice', delta=0):
        values = {'tenantId': tenant, 'jiacn': user, 'delta': delta, 'now': 123}
        def value(match):
            item = values[match.group(1)]
            return str(item) if isinstance(item, int) else "'" + item.replace("'", "''") + "'"
        return re.sub(r'#\{(\w+)\}', value, self.statements[name]) + ';'

    def balance(self, tenant='0', user='alice'):
        return self.command(self.mapper('balance', tenant, user))[0]

    def test_migration_preserves_each_balance_and_only_imports_production(self):
        self.command("INSERT INTO user_info VALUES (1,'alice',41,'0',1,2),(2,'bob',NULL,'0',3,4);")
        self.command('CALL cyf_import_legacy_point_account();')
        self.assertEqual(self.balance(), '41')
        self.assertEqual(self.balance(user='bob'), '0')
        self.assertEqual(self.balance(tenant='1'), '')
        self.assertEqual(self.command('SELECT point FROM user_info WHERE id=1;')[0], '41')
        self.command('CALL cyf_import_legacy_point_account();', success=False)
        self.assertEqual(self.balance(), '41')

    def test_existing_target_is_never_reset_or_reimported(self):
        self.command("INSERT INTO user_info VALUES (1,'alice',41,'0',1,2);")
        self.command(self.mapper('ensureAccount') + self.mapper('change', delta=9))
        self.command('CALL cyf_import_legacy_point_account();', success=False)
        self.assertEqual(self.balance(), '9')
        self.command(self.mapper('ensureAccount'))
        self.assertEqual(self.balance(), '9')

    def test_invalid_source_does_not_partially_import(self):
        self.command("INSERT INTO user_info VALUES (1,'alice',41,'0',1,2),(2,'bob',-1,'0',1,2);")
        self.command('CALL cyf_import_legacy_point_account();', success=False)
        self.assertEqual(self.command('SELECT COUNT(*) FROM point_account;')[0], '0')

    def test_nonproduction_source_identity_is_not_silently_imported_into_production(self):
        self.command("INSERT INTO user_info VALUES (1,'alice',41,'1',1,2);")
        self.command('CALL cyf_import_legacy_point_account();', success=False)
        self.assertEqual(self.command('SELECT COUNT(*) FROM point_account;')[0], '0')

    def test_duplicate_identity_does_not_partially_import(self):
        self.command("INSERT INTO user_info VALUES (1,'alice',41,'0',1,2),(2,'alice',50,'0',1,2);")
        self.command('CALL cyf_import_legacy_point_account();', success=False)
        self.assertEqual(self.command('SELECT COUNT(*) FROM point_account;')[0], '0')

    def test_same_identity_has_independent_production_and_grey_balance(self):
        self.command(self.mapper('ensureAccount') + self.mapper('change', delta=10))
        self.command(self.mapper('ensureAccount', tenant='1') + self.mapper('change', tenant='1', delta=4))
        self.assertEqual(self.balance(), '10')
        self.assertEqual(self.balance(tenant='1'), '4')
        self.assertEqual(self.balance(user='Alice'), '')
        self.assertEqual(self.balance(user='alice '), '')

    def test_negative_and_overflow_changes_are_refused(self):
        self.command(self.mapper('ensureAccount') + self.mapper('change', delta=2))
        self.assertEqual(self.command(self.mapper('change', delta=-3) + 'SELECT ROW_COUNT();')[0], '0')
        self.assertEqual(self.balance(), '2')
        self.assertEqual(self.command(self.mapper('change', delta=2147483647) + 'SELECT ROW_COUNT();')[0], '0')
        self.assertEqual(self.balance(), '2')

    def test_balance_and_business_record_roll_back_together(self):
        self.command('CREATE TABLE point_record (id bigint PRIMARY KEY, remain int NOT NULL);')
        self.command('START TRANSACTION;' + self.mapper('ensureAccount')
                     + self.mapper('lockBalance') + self.mapper('change', delta=3)
                     + 'INSERT INTO point_record VALUES(1,3);ROLLBACK;')
        self.assertEqual(self.balance(), '')
        self.assertEqual(self.command('SELECT COUNT(*) FROM point_record;')[0], '0')
        self.command('START TRANSACTION;' + self.mapper('ensureAccount')
                     + self.mapper('lockBalance') + self.mapper('change', delta=3)
                     + 'INSERT INTO point_record VALUES(1,3);COMMIT;')
        self.assertEqual(self.balance(), '3')
        self.assertEqual(self.command('SELECT remain FROM point_record;')[0], '3')

    def test_concurrent_changes_do_not_lose_balance_updates(self):
        def increment(_):
            self.command('START TRANSACTION;' + self.mapper('ensureAccount')
                         + self.mapper('lockBalance') + self.mapper('change', delta=1) + 'COMMIT;')
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(increment, range(16)))
        self.assertEqual(self.balance(), '16')
        self.assertEqual(self.command("SELECT version FROM point_account WHERE tenant_id='0' AND jiacn='alice';")[0], '16')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--socket', required=True)
    args, remaining = parser.parse_known_args()
    SOCKET = str(pathlib.Path(args.socket).resolve(strict=True))
    unittest.main(argv=[__file__, *remaining])
