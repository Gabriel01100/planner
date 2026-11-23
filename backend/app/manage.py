"""Administrative commands: credentials are printed once, never stored in plaintext."""
import argparse
import os
from .database import make_engine, migrate
from .security import Access

def main():
    parser = argparse.ArgumentParser(description='Administración local de CalenFleX')
    parser.add_argument('command', choices=['migrate', 'issue-token'])
    parser.add_argument('--owner')
    parser.add_argument('--scopes', nargs='+')
    parser.add_argument('--label', default='Conexión personal')
    args = parser.parse_args()
    engine = make_engine(os.getenv('DATABASE_URL', 'sqlite:///./calenflex.db'))
    migrate(engine)
    if args.command == 'issue-token':
        if not args.owner or not args.scopes:
            parser.error('issue-token requiere --owner y --scopes')
        print(Access(engine).issue(args.owner, args.scopes, args.label))
    else:
        print('Migraciones aplicadas sin reemplazar datos existentes.')
    engine.dispose()

if __name__ == '__main__':
    main()
