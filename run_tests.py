"""
Ejecutor de pruebas unitarias para OmniVault Desktop.
Ejecuta la suite completa de pruebas en tests/ y reporta los resultados.
"""

import os
import sys
import unittest

# Manejo robusto de salida UTF-8 en consolas Windows
if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
if sys.stderr and hasattr(sys.stderr, 'reconfigure'):
    try:
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

def run():
    print("=" * 60)
    print("[*] Ejecutando suite de pruebas de OmniVault Desktop...")
    print("=" * 60)

    loader = unittest.TestLoader()
    suite = loader.discover(start_dir="tests", pattern="test_*.py")

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print("\n" + "=" * 60)
    if result.wasSuccessful():
        print(f"[OK] Todas las pruebas ({result.testsRun}) pasaron exitosamente!")
        return 0
    else:
        print(f"[ERROR] Pruebas fallidas: {len(result.failures)}, Errores: {len(result.errors)}")
        return 1

if __name__ == "__main__":
    code = run()
    sys.exit(code)
