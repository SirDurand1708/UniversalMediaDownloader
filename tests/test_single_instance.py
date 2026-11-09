"""
Pruebas unitarias para el módulo de Instancia Única (single_instance.py).
"""

import time
import unittest
from single_instance import (
    notify_running_instance,
    start_single_instance_server,
    stop_single_instance_server,
)

TEST_PORT = 48731


class TestSingleInstance(unittest.TestCase):
    def test_no_running_instance(self):
        # Si no hay servidor corriendo en el puerto, notify debe retornar False
        running = notify_running_instance(port=TEST_PORT)
        self.assertFalse(running)

    def test_single_instance_communication(self):
        restored = []

        def on_restore():
            restored.append(True)

        # Iniciar servidor en puerto de prueba
        server = start_single_instance_server(on_restore, port=TEST_PORT)
        self.assertIsNotNone(server)

        try:
            # Ahora notify_running_instance debe detectar el servidor y retornar True
            running = notify_running_instance(port=TEST_PORT)
            self.assertTrue(running)

            # Esperar a que el hilo del servidor ejecute el callback
            time.sleep(0.1)
            self.assertEqual(len(restored), 1)
        finally:
            stop_single_instance_server(server)

        # Tras cerrar el servidor, notify debe retornar False
        time.sleep(0.05)
        running_after = notify_running_instance(port=TEST_PORT)
        self.assertFalse(running_after)


if __name__ == "__main__":
    unittest.main()
