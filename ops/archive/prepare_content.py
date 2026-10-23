#!/usr/bin/env python3
"""Prepare an external book data package; no network, database or application build."""
import argparse
import hashlib
import json
import pathlib
import re


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode('utf-8')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field: ' + key)
        result[key] = value
    return result


def valid_title(value):
    return (isinstance(value, str) and bool(value.strip()) and value == value.strip()
            and len(value.encode('utf-16-le')) // 2 <= 255
            and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value))


def prepare(source_bytes):
    book = json.loads(source_bytes.decode('utf-8'), object_pairs_hook=unique_object)
    if not isinstance(book, dict):
        raise ValueError('Book must be an object')
    if set(book) - {'workId', 'editionId', 'title', 'preface', 'chapters'}:
        raise ValueError('Unknown book fields')
    for key, maximum in [('workId', 64), ('editionId', 96)]:
        value = book[key]
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', value) or len(value) > maximum:
            raise ValueError('Invalid ' + key)
    if not valid_title(book['title']):
        raise ValueError('Invalid title')
    edition = book['editionId']

    def block(raw, number):
        if not isinstance(raw, dict) or set(raw) != {'title', 'paragraphs'} or not isinstance(raw['paragraphs'], list) or not raw['paragraphs']:
            raise ValueError('Block requires title and nonempty paragraphs')
        if not valid_title(raw['title']):
            raise ValueError('Invalid block title')
        identity = edition + ('-preface' if number == 0 else '-c%03d' % number)
        paragraphs = []
        for index, text in enumerate(raw['paragraphs'], 1):
            if not isinstance(text, str) or not text:
                raise ValueError('Paragraph text must be nonempty')
            encoded = text.encode('utf-8')
            paragraphs.append(dict(paragraphId=identity + '-p%04d' % index, text=text,
                                   sha256=sha(encoded), utf8ByteLength=len(encoded)))
        result = dict(title=raw['title'], paragraphs=paragraphs, paragraphCount=len(paragraphs),
                      utf8ByteLength=sum(p['utf8ByteLength'] for p in paragraphs))
        if number == 0:
            result['prefaceId'] = identity
        else:
            result.update(chapterId=identity, number=number)
        return result

    if not isinstance(book['chapters'], list) or not book['chapters']:
        raise ValueError('At least one chapter is required')
    chapters = [block(value, i) for i, value in enumerate(book['chapters'], 1)]
    preface = block(book['preface'], 0) if book.get('preface') is not None else None
    pp = preface['paragraphCount'] if preface else 0
    pb = preface['utf8ByteLength'] if preface else 0
    cp = sum(c['paragraphCount'] for c in chapters)
    cb = sum(c['utf8ByteLength'] for c in chapters)
    manifest = dict(schemaVersion=1, workId=book['workId'], editionId=edition, title=book['title'],
                    chapters=chapters, preface=preface, chapterCount=len(chapters),
                    prefaceParagraphCount=pp, prefaceUtf8ByteLength=pb,
                    chapterParagraphCount=cp, chapterUtf8ByteLength=cb,
                    readerParagraphCount=pp + cp, readerUtf8ByteLength=pb + cb,
                    source=dict(file='book.json', page='', upstreamRepo='', upstreamCommit='',
                                sha256=sha(source_bytes), utf8ByteLength=len(source_bytes), excludedNoticeLines=[]))
    manifest['manifestSha256'] = sha(canonical(manifest))
    return canonical(manifest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('book', type=pathlib.Path)
    parser.add_argument('manifest', type=pathlib.Path)
    args = parser.parse_args()
    content = prepare(args.book.read_bytes())
    with args.manifest.open('xb') as output:
        output.write(content)
    print(sha(content))
