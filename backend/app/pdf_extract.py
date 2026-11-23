"""Isolated PDF text decoder. Reads stdin only; no credentials or remote fetching."""
import json
import sys
from io import BytesIO


def main():
    import resource
    resource.setrlimit(resource.RLIMIT_AS, (256 * 1024 * 1024, 256 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CPU, (3, 3))
    resource.setrlimit(resource.RLIMIT_FSIZE, (0, 0))
    from pypdf import PdfReader
    payload = sys.stdin.buffer.read(2_000_001)
    if len(payload) > 2_000_000:
        raise ValueError('Límite de archivo')
    reader = PdfReader(BytesIO(payload), strict=True)
    if reader.is_encrypted or len(reader.pages) > 20:
        raise ValueError('PDF no compatible')
    parts = []
    for page in reader.pages:
        parts.append(page.extract_text() or '')
        if sum(len(t) for t in parts) > 20000:
            raise ValueError('Límite de texto')
    text = '\n'.join(parts)
    if not text.strip():
        raise ValueError('PDF sin texto')
    sys.stdout.write(json.dumps(text, ensure_ascii=False))

if __name__ == '__main__':
    main()
