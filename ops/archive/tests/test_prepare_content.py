import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('prepare_content', Path(__file__).parents[1] / 'prepare_content.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PreparationTest(unittest.TestCase):
    def book(self, count=2):
        return dict(workId='sample-book', editionId='sample-book-v1', title='示例书',
                    chapters=[dict(title=f'第{i}章', paragraphs=['甲😊', '乙']) for i in range(1, count + 1)])

    def test_optional_preface_and_more_than_120_chapters(self):
        for count in (1, 2, 121):
            source = module.canonical(self.book(count))
            output = module.prepare(source)
            manifest = json.loads(output)
            digest = manifest.pop('manifestSha256')
            self.assertEqual(digest, module.sha(module.canonical(manifest)))
            self.assertEqual(count, manifest['chapterCount'])
            self.assertEqual(0, manifest['prefaceParagraphCount'])
            self.assertEqual(count * 2, manifest['readerParagraphCount'])
            self.assertEqual(count * 10, manifest['readerUtf8ByteLength'])
            self.assertEqual(module.sha(source), manifest['source']['sha256'])
            self.assertEqual(output, module.prepare(source))

    def test_preface_has_its_own_identity_and_metrics(self):
        book = self.book()
        book['preface'] = dict(title='序', paragraphs=['前言'])
        manifest = json.loads(module.prepare(module.canonical(book)))
        self.assertEqual('sample-book-v1-preface', manifest['preface']['prefaceId'])
        self.assertEqual(1, manifest['prefaceParagraphCount'])
        self.assertEqual(6, manifest['prefaceUtf8ByteLength'])

    def test_rejects_duplicate_fields_and_invalid_input(self):
        with self.assertRaises(ValueError):
            module.prepare(b'{"workId":"a","workId":"b"}')
        for change in (dict(chapters=[]), dict(title=' a '), dict(title='a\x00'),
                       dict(workId='../book'), dict(extra=1), dict(preface={'title': '序', 'paragraphs': []})):
            with self.subTest(change=change), self.assertRaises(ValueError):
                module.prepare(module.canonical({**self.book(), **change}))


if __name__ == '__main__':
    unittest.main()
